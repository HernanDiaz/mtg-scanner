package com.hernan.mtgscanner.scan

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageCreate
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
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
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
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

/**
 * Una imagen derecha lista para reconocer ([cgImage], para Vision) y sus píxeles para el
 * [ArtSampler] (ilustración, borde y símbolo de edición), que se leen solo si hacen falta.
 * [cgImage] solo vale mientras dura el reconocimiento de ese fotograma.
 */
class PreparedImage(
    val cgImage: CGImageRef, val width: Int, val height: Int,
    /** Los píxeles RGBA, si ya se tienen (fotogramas de la cámara). */
    private val knownPixels: ByteArray? = null,
) : ArtSampler {

    /** RGBA, la primera fila en memoria es la de arriba. */
    private val pixels: ByteArray by lazy { knownPixels ?: rgba(cgImage, width, height) }

    override fun rgb(x: Int, y: Int): Int {
        val p = pixels
        val i = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        return ((p[i].toInt() and 0xFF) shl 16) or ((p[i + 1].toInt() and 0xFF) shl 8) or (p[i + 2].toInt() and 0xFF)
    }

    /**
     * El fotograma, que sigue valiendo después (para guardarlo como foto). Retiene la imagen en
     * vez de copiar sus píxeles: con las fotos de cartas activadas se pide en cada fotograma
     * leído y copiar 8 MB cada vez frenaba el escáner; el JPEG solo se hace si se guarda.
     */
    fun frame(): ScanFrame = CgFrame(cgImage, width, height)

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

        /**
         * Un fotograma de la cámara (BGRA y apaisado, como sale del sensor) copiado y girado a
         * vertical. Se copia en vez de envolverlo en un CIImage: desde Kotlin ese objeto no se
         * suelta hasta que pasa el recolector de memoria (segundos), y mientras tanto retiene el
         * fotograma; la cámara tiene pocos y deja de entregar más (el escáner se paraba ~9 s
         * entre carta y carta). null si el fotograma no es BGRA. Hay que soltar [cgImage].
         */
        fun fromCamera(pb: CVPixelBufferRef): PreparedImage? {
            if (CVPixelBufferGetPixelFormatType(pb) != kCVPixelFormatType_32BGRA) return null
            CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly)
            val raw: ByteArray
            val w: Int
            val h: Int
            val bpr: Int
            try {
                w = CVPixelBufferGetWidth(pb).toInt()
                h = CVPixelBufferGetHeight(pb).toInt()
                bpr = CVPixelBufferGetBytesPerRow(pb).toInt()
                val base = CVPixelBufferGetBaseAddress(pb) ?: return null
                raw = ByteArray(bpr * h)
                raw.usePinned { memcpy(it.addressOf(0), base, (bpr * h).toULong()) }
            } finally {
                CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly)
            }
            // Girado 90 grados a la derecha: la imagen vertical mide h x w. BGRA -> RGBA.
            val uw = h
            val uh = w
            val out = ByteArray(uw * uh * 4)
            val step = uw * 4
            for (ry in 0 until h) {
                var src = ry * bpr
                var dst = (h - 1 - ry) * 4
                for (rx in 0 until w) {
                    out[dst] = raw[src + 2]
                    out[dst + 1] = raw[src + 1]
                    out[dst + 2] = raw[src]
                    out[dst + 3] = -1
                    src += 4
                    dst += step
                }
            }
            return fromRgba(out, uw, uh)
        }

        /** Imagen de unos píxeles RGBA (copiados: la imagen no depende del array). */
        private fun fromRgba(rgba: ByteArray, w: Int, h: Int): PreparedImage {
            val space = CGColorSpaceCreateDeviceRGB()
            val data = rgba.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), rgba.size.toLong()) }
            val provider = CGDataProviderCreateWithCFData(data)
            val cg = CGImageCreate(w.toULong(), h.toULong(), 8u, 32u, (w * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value, provider, null, false, CGColorRenderingIntent.kCGRenderingIntentDefault)
            CGDataProviderRelease(provider)
            CFRelease(data)
            CGColorSpaceRelease(space)
            return PreparedImage(cg ?: error("No se puede crear la imagen"), w, h, rgba)
        }

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

/**
 * Un fotograma de iPhone para guardarlo como foto: la imagen retenida, que se suelta cuando
 * este objeto deja de usarse.
 */
@OptIn(ExperimentalNativeApi::class)
class CgFrame(cgImage: CGImageRef, override val width: Int, override val height: Int) : ScanFrame {
    private val cg: CGImageRef = CGImageRetain(cgImage)!!
    @Suppress("unused") // existe para soltar la imagen
    private val cleaner = createCleaner(cg) { CGImageRelease(it) }

    override fun jpeg(maxSide: Int, quality: Int): ByteArray {
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
