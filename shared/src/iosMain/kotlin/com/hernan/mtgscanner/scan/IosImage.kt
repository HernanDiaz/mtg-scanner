package com.hernan.mtgscanner.scan

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Una imagen derecha lista para reconocer ([cgImage], para Vision) y sus píxeles para el
 * [ArtSampler] (ilustración, borde y símbolo de edición), que se leen solo si hacen falta.
 * [cgImage] solo vale mientras dura el reconocimiento de ese fotograma.
 */
class PreparedImage(val cgImage: CGImageRef, val width: Int, val height: Int) : ArtSampler {

    /** RGBA, la primera fila en memoria es la de arriba. */
    private val pixels: ByteArray by lazy { rgba(cgImage, width, height) }

    override fun rgb(x: Int, y: Int): Int {
        val p = pixels
        val i = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        return ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
    }

    /** Copia del fotograma que sigue valiendo después (para guardarlo como foto). */
    fun frame(): ScanFrame = IosFrame(pixels.copyOf(), width, height)

    companion object {
        /** Igual que las evaluaciones de Android (fitTo(1920)). */
        const val MAX_SIDE = 1920

        /** Una foto (con su orientación EXIF), reducida a [MAX_SIDE]. La imagen vive con la UIImage nueva. */
        fun from(image: UIImage): Pair<PreparedImage, UIImage> {
            val (w0, h0) = image.size.useContents { width to height }
            val k = min(1.0, MAX_SIDE / max(w0, h0))
            val w = (w0 * k).roundToInt().coerceAtLeast(1)
            val h = (h0 * k).roundToInt().coerceAtLeast(1)
            // Redibujar aplica la orientación de la foto y la reduce.
            val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
            val renderer = UIGraphicsImageRenderer(size = CGSizeMake(w.toDouble(), h.toDouble()), format = format)
            val upright = renderer.imageWithActions { _ -> image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble())) }
            val cg = upright.CGImage ?: error("No se puede leer la foto")
            return PreparedImage(cg, w, h) to upright
        }

        fun from(cg: CGImageRef) = PreparedImage(cg, CGImageGetWidth(cg).toInt(), CGImageGetHeight(cg).toInt())

        private fun rgba(cg: CGImageRef, w: Int, h: Int): ByteArray {
            val pixels = ByteArray(w * h * 4)
            val space = CGColorSpaceCreateDeviceRGB()
            pixels.usePinned { pin ->
                val ctx = CGBitmapContextCreate(pin.addressOf(0), w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
                    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value)
                CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cg)
                CGContextRelease(ctx)
            }
            CGColorSpaceRelease(space)
            return pixels
        }
    }
}

/** Un fotograma de iPhone (píxeles RGBA) para guardarlo como foto. */
class IosFrame(private val pixels: ByteArray, override val width: Int, override val height: Int) : ScanFrame {

    override fun jpeg(maxSide: Int, quality: Int): ByteArray {
        val space = CGColorSpaceCreateDeviceRGB()
        val cg = pixels.usePinned { pin ->
            val ctx = CGBitmapContextCreate(pin.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value)
            CGBitmapContextCreateImage(ctx).also { CGContextRelease(ctx) }
        }
        CGColorSpaceRelease(space)
        cg ?: return ByteArray(0)
        try {
            var image = UIImage.imageWithCGImage(cg)
            val side = max(width, height)
            if (side > maxSide) {
                val k = maxSide.toDouble() / side
                val w = width * k
                val h = height * k
                val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
                image = UIGraphicsImageRenderer(size = CGSizeMake(w, h), format = format)
                    .imageWithActions { _ -> image.drawInRect(CGRectMake(0.0, 0.0, w, h)) }
            }
            return UIImageJPEGRepresentation(image, quality / 100.0)?.toByteArray() ?: ByteArray(0)
        } finally {
            CGImageRelease(cg)
        }
    }
}

/**
 * Texto de la imagen con Vision (el OCR de Apple), en las líneas que espera el
 * reconocimiento: coordenadas en píxeles con el origen arriba a la izquierda.
 */
fun recognizeText(cg: CGImageRef, width: Int, height: Int): List<OcrLine> {
    val request = VNRecognizeTextRequest().apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        // Los nombres de cartas no son palabras del diccionario: sin autocorrección
        usesLanguageCorrection = false
        recognitionLanguages = listOf("en-US", "es-ES", "fr-FR", "de-DE", "it-IT", "pt-BR")
    }
    val handler = VNImageRequestHandler(cGImage = cg, options = emptyMap<Any?, Any?>())
    if (!handler.performRequests(listOf(request), null)) return emptyList()
    val w = width.toDouble()
    val h = height.toDouble()
    return request.results.orEmpty().filterIsInstance<VNRecognizedTextObservation>().mapNotNull { obs ->
        val text = (obs.topCandidates(1u).firstOrNull() as? VNRecognizedText)?.string ?: return@mapNotNull null
        // Vision da coordenadas de 0 a 1 con el origen abajo a la izquierda
        obs.boundingBox.useContents {
            OcrLine(
                text = text,
                left = (origin.x * w).toInt(),
                top = ((1 - origin.y - size.height) * h).toInt(),
                right = ((origin.x + size.width) * w).toInt(),
                bottom = ((1 - origin.y) * h).toInt(),
            )
        }
    }
}

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
