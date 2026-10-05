package com.hernan.mtgscanner.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/**
 * Pasa cada fotograma de la cámara por ML Kit (en el dispositivo) y luego
 * por [FrameRecognizer]. Con STRATEGY_KEEP_ONLY_LATEST se procesa siempre el
 * fotograma más reciente, a la velocidad que dé el OCR.
 */
class TextAnalyzer(
    private val recognizer: FrameRecognizer,
    private val executor: Executor,
    /** Tamaño en píxeles de la vista de la cámara, para situar el marco guía. */
    private val viewSize: () -> Pair<Int, Int>?,
    /** [frame]: el fotograma derecho; solo vale durante la llamada (luego se libera). */
    private val onResult: (result: FrameResult?, rawTopLine: String?, frame: () -> ScanFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        // Las cajas del OCR vienen en la imagen ya girada.
        val (imageW, imageH) = if (rotation % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
        val image = InputImage.fromMediaImage(media, rotation)
        ocr.process(image)
            .addOnSuccessListener(executor) { text ->
                var lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                }
                viewSize()?.let { (w, h) -> lines = GuideRegion.filter(lines, GuideRegion.inImage(w, h, imageW, imageH)) }
                // La imagen en color solo se convierte si hace falta comparar ilustraciones
                // (edición dudosa): convertir cada fotograma costaría ~20 ms.
                val bitmap by lazy { proxy.toBitmap() }
                val art = ArtSampler { x, y ->
                    val bmp = bitmap
                    val (sx, sy) = uprightToSensor(x, y, rotation, bmp.width, bmp.height)
                    bmp.getPixel(sx.coerceIn(0, bmp.width - 1), sy.coerceIn(0, bmp.height - 1)) and 0xFFFFFF
                }
                val result = recognizer.recognize(lines, art)
                onResult(result, lines.minByOrNull { it.top }?.text) {
                    val bmp = bitmap
                    BitmapFrame(
                        if (rotation == 0) bmp.copy(Bitmap.Config.ARGB_8888, false)
                        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                    )
                }
            }
            .addOnFailureListener(executor) { onResult(null, null) { BitmapFrame(proxy.toBitmap()) } }
            .addOnCompleteListener(executor) { proxy.close() }
    }

    fun close() = ocr.close()
}

/** Un fotograma de Android (Bitmap) para guardarlo como foto. */
class BitmapFrame(val bitmap: Bitmap) : ScanFrame {
    override val width get() = bitmap.width
    override val height get() = bitmap.height

    override fun jpeg(maxSide: Int, quality: Int): ByteArray {
        val side = maxOf(bitmap.width, bitmap.height)
        val scaled = if (side <= maxSide) bitmap else {
            val k = maxSide.toFloat() / side
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).toInt(), (bitmap.height * k).toInt(), true)
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }
}
