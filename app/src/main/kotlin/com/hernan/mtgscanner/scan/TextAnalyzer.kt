package com.hernan.mtgscanner.scan

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
    private val onResult: (result: FrameResult?, rawTopLine: String?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Tamaño en píxeles de la vista de la cámara, para situar el marco guía. */
    @Volatile var viewSize: Pair<Int, Int>? = null

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
                viewSize?.let { (w, h) -> lines = GuideRegion.filter(lines, GuideRegion.inImage(w, h, imageW, imageH)) }
                // La imagen en color solo se convierte si hace falta comparar ilustraciones
                // (edición dudosa): convertir cada fotograma costaría ~20 ms.
                val bitmap by lazy { proxy.toBitmap() }
                val art = ArtSampler { l, t, r, b ->
                    val bmp = bitmap
                    ArtSignature.compute(l, t, r, b) { x, y ->
                        val (sx, sy) = uprightToSensor(x, y, rotation, bmp.width, bmp.height)
                        bmp.getPixel(sx.coerceIn(0, bmp.width - 1), sy.coerceIn(0, bmp.height - 1)) and 0xFFFFFF
                    }
                }
                val result = recognizer.recognize(lines, art)
                onResult(result, lines.minByOrNull { it.top }?.text)
            }
            .addOnFailureListener(executor) { onResult(null, null) }
            .addOnCompleteListener(executor) { proxy.close() }
    }

    fun close() = ocr.close()
}
