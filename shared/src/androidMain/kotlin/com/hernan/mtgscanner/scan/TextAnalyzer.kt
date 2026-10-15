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
    /** Lo que tardaron el OCR y el reconocimiento de cada fotograma, en ms. */
    private val onTiming: (ocrMs: Long, recognizeMs: Long) -> Unit = { _, _ -> },
) : ImageAnalysis.Analyzer {

    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        // Las cajas del OCR vienen en la imagen ya girada.
        val (imageW, imageH) = if (rotation % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
        val started = android.os.SystemClock.elapsedRealtime()
        val guide = viewSize()?.let { (w, h) -> GuideRegion.inImage(w, h, imageW, imageH) }
        // Como en el iPhone, el OCR solo lee la zona del marco guía: menos píxeles, más rápido.
        val cropped = guide?.let { runCatching { cropToGuide(proxy, rotation, imageW, imageH, it) }.getOrNull() }
        val image = cropped?.image ?: InputImage.fromMediaImage(media, rotation)
        val dx = cropped?.left ?: 0
        val dy = cropped?.top ?: 0
        ocr.process(image)
            .addOnSuccessListener(executor) { text ->
                val ocrDone = android.os.SystemClock.elapsedRealtime()
                var lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
                }
                guide?.let { lines = GuideRegion.filter(lines, it) }
                // La imagen en color solo se convierte si hace falta comparar ilustraciones
                // (edición dudosa): convertir cada fotograma costaría ~20 ms.
                val bitmap by lazy { proxy.toBitmap() }
                val art = ArtSampler { x, y ->
                    val bmp = bitmap
                    val (sx, sy) = uprightToSensor(x, y, rotation, bmp.width, bmp.height)
                    bmp.getPixel(sx.coerceIn(0, bmp.width - 1), sy.coerceIn(0, bmp.height - 1)) and 0xFFFFFF
                }
                val result = recognizer.recognize(lines, art)
                onTiming(ocrDone - started, android.os.SystemClock.elapsedRealtime() - ocrDone)
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

    /** La imagen recortada para el OCR y dónde empieza en la imagen derecha. */
    private class Cropped(val image: InputImage, val left: Int, val top: Int)

    /**
     * Copia la zona del marco guía del plano de luminancia (el OCR no usa el color) a un
     * NV21 con el color neutro. Null si la cámara no da un formato que se pueda recortar.
     */
    private fun cropToGuide(proxy: ImageProxy, rotation: Int, imageW: Int, imageH: Int, guide: GuideRegion.Rect): Cropped? {
        val plane = proxy.planes.firstOrNull() ?: return null
        if (plane.pixelStride != 1) return null
        val sw = proxy.width
        val sh = proxy.height
        val l = guide.left.toInt().coerceIn(0, imageW - 1)
        val t = guide.top.toInt().coerceIn(0, imageH - 1)
        val r = guide.right.toInt().coerceIn(l + 1, imageW)
        val b = guide.bottom.toInt().coerceIn(t + 1, imageH)
        // El mismo rectángulo en el sensor; NV21 necesita origen y tamaño pares.
        val (ax, ay) = uprightToSensor(l, t, rotation, sw, sh)
        val (bx, by) = uprightToSensor(r - 1, b - 1, rotation, sw, sh)
        val x0 = minOf(ax, bx) and 1.inv()
        val y0 = minOf(ay, by) and 1.inv()
        val cw = (maxOf(ax, bx) + 1 - x0) and 1.inv()
        val ch = (maxOf(ay, by) + 1 - y0) and 1.inv()
        if (cw < 32 || ch < 32) return null
        val src = plane.buffer.duplicate()
        val stride = plane.rowStride
        val nv21 = ByteArray(cw * ch * 3 / 2)
        for (row in 0 until ch) {
            src.position((y0 + row) * stride + x0)
            src.get(nv21, row * cw, cw)
        }
        java.util.Arrays.fill(nv21, cw * ch, nv21.size, 128.toByte())
        // Esquina superior izquierda del recorte ya girado, en la imagen derecha.
        val (ux1, uy1) = sensorToUpright(x0, y0, rotation, sw, sh)
        val (ux2, uy2) = sensorToUpright(x0 + cw - 1, y0 + ch - 1, rotation, sw, sh)
        val image = InputImage.fromByteArray(nv21, cw, ch, rotation, InputImage.IMAGE_FORMAT_NV21)
        return Cropped(image, minOf(ux1, ux2), minOf(uy1, uy2))
    }

    /** Lo contrario de [uprightToSensor]. */
    private fun sensorToUpright(x: Int, y: Int, rotation: Int, sensorW: Int, sensorH: Int): Pair<Int, Int> = when (rotation) {
        90 -> (sensorH - 1 - y) to x
        180 -> (sensorW - 1 - x) to (sensorH - 1 - y)
        270 -> y to (sensorW - 1 - x)
        else -> x to y
    }
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
