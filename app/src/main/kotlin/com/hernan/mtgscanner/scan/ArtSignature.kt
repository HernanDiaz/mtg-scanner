package com.hernan.mtgscanner.scan

import kotlin.math.sqrt

/**
 * Miniatura de una ilustración (16×12 en color) para distinguir ediciones de la
 * misma carta. Las de referencia las genera tools/build_art_signatures.py a partir
 * de los "art_crop" de Scryfall (recortados un 4 % por lado, 4 bits por canal).
 *
 * Se compara por correlación normalizada, probando desplazamientos de ±1 celda
 * para tolerar que el recorte de la foto no coincida exactamente. Medido con fotos
 * simuladas: acierta la ilustración el 84 % de las veces (un pHash, el 64 %).
 */
class ArtSignature(private val values: FloatArray) {

    /** Misma codificación que las de referencia (4 bits por canal): 288 bytes para guardar. */
    fun toPacked(): ByteArray = ByteArray(values.size / 2) { i ->
        val hi = values[2 * i].toInt().coerceIn(0, 255) shr 4
        val lo = values[2 * i + 1].toInt().coerceIn(0, 255) shr 4
        ((hi shl 4) or lo).toByte()
    }

    /** Correlación máxima entre −1 y 1 (1 = idénticas salvo brillo y contraste). */
    fun similarity(other: ArtSignature): Double {
        var best = -1.0
        for (dy in -SHIFT..SHIFT) for (dx in -SHIFT..SHIFT) {
            best = maxOf(best, correlation(other, dx, dy))
        }
        return best
    }

    /** Correlación de la zona común cuando esta se desplaza (dx, dy) celdas respecto a [other]. */
    private fun correlation(other: ArtSignature, dx: Int, dy: Int): Double {
        val x0 = maxOf(0, dx); val x1 = W + minOf(0, dx)
        val y0 = maxOf(0, dy); val y1 = H + minOf(0, dy)
        var n = 0
        var sa = 0.0; var sb = 0.0
        for (y in y0 until y1) for (x in x0 until x1) for (c in 0 until 3) {
            sa += values[idx(x, y, c)]; sb += other.values[idx(x - dx, y - dy, c)]; n++
        }
        val ma = sa / n; val mb = sb / n
        var cov = 0.0; var va = 0.0; var vb = 0.0
        for (y in y0 until y1) for (x in x0 until x1) for (c in 0 until 3) {
            val a = values[idx(x, y, c)] - ma
            val b = other.values[idx(x - dx, y - dy, c)] - mb
            cov += a * b; va += a * a; vb += b * b
        }
        return if (va == 0.0 || vb == 0.0) 0.0 else cov / sqrt(va * vb)
    }

    companion object {
        const val W = 16
        const val H = 12
        private const val SHIFT = 1
        /** Muestras por celda y lado al reducir la foto (aproxima el promedio de la zona). */
        private const val SAMPLES = 8

        private fun idx(x: Int, y: Int, c: Int) = (y * W + x) * 3 + c

        /**
         * Miniatura de la región [left, right) × [top, bottom) de la imagen derecha.
         * [rgbAt] devuelve el color 0xRRGGBB del punto dado.
         */
        fun compute(left: Float, top: Float, right: Float, bottom: Float, rgbAt: (x: Int, y: Int) -> Int): ArtSignature {
            val w = right - left
            val h = bottom - top
            val v = FloatArray(W * H * 3)
            val n = SAMPLES * SAMPLES
            for (cy in 0 until H) for (cx in 0 until W) {
                var r = 0; var g = 0; var b = 0
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    val x = left + (cx + (sx + 0.5f) / SAMPLES) / W * w
                    val y = top + (cy + (sy + 0.5f) / SAMPLES) / H * h
                    val c = rgbAt(x.toInt(), y.toInt())
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                v[idx(cx, cy, 0)] = r.toFloat() / n
                v[idx(cx, cy, 1)] = g.toFloat() / n
                v[idx(cx, cy, 2)] = b.toFloat() / n
            }
            return ArtSignature(v)
        }

        /** Miniatura de referencia del catálogo: 4 bits por canal, dos valores por byte. */
        fun fromPacked(bytes: ByteArray): ArtSignature {
            val v = FloatArray(W * H * 3)
            for (i in v.indices) {
                val byte = bytes[i / 2].toInt() and 0xFF
                val q = if (i % 2 == 0) byte shr 4 else byte and 0x0F
                v[i] = q * 16f + 8f
            }
            return ArtSignature(v)
        }
    }
}

/**
 * La cámara entrega la imagen en la orientación del sensor y el OCR trabaja con
 * la imagen ya girada [rotation] grados (en sentido horario). Traduce un punto de
 * la imagen derecha a la del sensor, de tamaño [sensorW]×[sensorH].
 */
fun uprightToSensor(x: Int, y: Int, rotation: Int, sensorW: Int, sensorH: Int): Pair<Int, Int> = when (rotation) {
    90 -> y to (sensorH - 1 - x)
    180 -> (sensorW - 1 - x) to (sensorH - 1 - y)
    270 -> (sensorW - 1 - y) to x
    else -> x to y
}
