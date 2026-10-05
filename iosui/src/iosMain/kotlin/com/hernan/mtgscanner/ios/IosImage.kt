package com.hernan.mtgscanner.ios

import com.hernan.mtgscanner.scan.ArtSampler
import com.hernan.mtgscanner.scan.OcrLine
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
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
 * Una foto lista para reconocer: derecha (con la orientación aplicada), con el lado
 * mayor de como mucho [MAX_SIDE] como en Android, y sus píxeles para el [ArtSampler]
 * (ilustración, borde y símbolo de edición).
 */
class PreparedImage private constructor(
    val cgImage: CGImageRef,
    val width: Int,
    val height: Int,
    private val pixels: ByteArray,
) : ArtSampler {

    override fun rgb(x: Int, y: Int): Int {
        val i = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        return ((pixels[i].toInt() and 0xFF) shl 16) or ((pixels[i + 1].toInt() and 0xFF) shl 8) or (pixels[i + 2].toInt() and 0xFF)
    }

    companion object {
        /** Igual que las evaluaciones de Android (fitTo(1920)). */
        const val MAX_SIDE = 1920

        fun from(image: UIImage): PreparedImage {
            val (w0, h0) = image.size.useContents { width to height }
            val k = min(1.0, MAX_SIDE / max(w0, h0))
            val w = (w0 * k).roundToInt().coerceAtLeast(1)
            val h = (h0 * k).roundToInt().coerceAtLeast(1)
            // Redibujar aplica la orientación de la foto (EXIF) y la reduce.
            val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
            val renderer = UIGraphicsImageRenderer(size = CGSizeMake(w.toDouble(), h.toDouble()), format = format)
            val upright = renderer.imageWithActions { _ -> image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble())) }
            val cg = upright.CGImage ?: error("No se puede leer la foto")

            // RGBA, la primera fila en memoria es la de arriba.
            val pixels = ByteArray(w * h * 4)
            val space = CGColorSpaceCreateDeviceRGB()
            pixels.usePinned { pin ->
                val ctx = CGBitmapContextCreate(pin.addressOf(0), w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
                    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value)
                CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cg)
                CGContextRelease(ctx)
            }
            CGColorSpaceRelease(space)
            return PreparedImage(cg, w, h, pixels)
        }
    }
}

/**
 * Texto de la foto con Vision (el OCR de Apple), en las líneas que espera el
 * reconocimiento: coordenadas en píxeles con el origen arriba a la izquierda.
 */
fun recognizeText(image: PreparedImage): List<OcrLine> {
    val request = VNRecognizeTextRequest().apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        // Los nombres de cartas no son palabras del diccionario: sin autocorrección
        usesLanguageCorrection = false
        recognitionLanguages = listOf("en-US", "es-ES", "fr-FR", "de-DE", "it-IT", "pt-BR")
    }
    val handler = VNImageRequestHandler(cGImage = image.cgImage, options = emptyMap<Any?, Any?>())
    if (!handler.performRequests(listOf(request), null)) error("Vision no ha podido leer la foto")
    val w = image.width.toDouble()
    val h = image.height.toDouble()
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

/** JPEG de la foto, para mostrarla en Compose. */
fun UIImage.jpegBytes(quality: Double = 0.7): ByteArray? = UIImageJPEGRepresentation(this, quality)?.toByteArray()

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
