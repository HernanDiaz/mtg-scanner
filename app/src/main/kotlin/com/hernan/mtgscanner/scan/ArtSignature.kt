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
 *
 * La correlación no se hace sobre R, G y B sino sobre luminancia y dos ejes de color
 * (rojo-verde, azul-amarillo), cada uno normalizado por separado: así no influye el
 * tinte de la luz ni el reflejo de una funda, que confundían ilustraciones parecidas
 * en fotos reales (Hymn to Tourach, Snow-Covered Swamp). Con fotos del móvil y los
 * conjuntos simulados: ninguna decisión segura errónea (antes 3) y 17 aciertos más.
 */
class ArtSignature(private val values: FloatArray) {

    /** Misma codificación que las de referencia (4 bits por canal): 288 bytes para guardar. */
    fun toPacked(): ByteArray = ByteArray(values.size / 2) { i ->
        val hi = values[2 * i].toInt().coerceIn(0, 255) shr 4
        val lo = values[2 * i + 1].toInt().coerceIn(0, 255) shr 4
        ((hi shl 4) or lo).toByte()
    }

    /** Luminancia, rojo-verde y azul-amarillo, cada plano con media 0 y desviación 1. */
    private val features: FloatArray by lazy { normalized(FloatArray(values.size)) }

    /**
     * Las mismas características en [f] (sin guardarlas: para recorrer miles de
     * huellas sin ocupar memoria).
     */
    private fun normalized(f: FloatArray): FloatArray {
        for (i in 0 until W * H) {
            val r = values[3 * i]; val g = values[3 * i + 1]; val b = values[3 * i + 2]
            f[3 * i] = 0.299f * r + 0.587f * g + 0.114f * b
            f[3 * i + 1] = r - g
            f[3 * i + 2] = b - (r + g) / 2
        }
        for (c in 0 until 3) {
            var mean = 0.0
            for (i in 0 until W * H) mean += f[3 * i + c]
            mean /= W * H
            var variance = 0.0
            for (i in 0 until W * H) { val d = f[3 * i + c] - mean; variance += d * d }
            val sd = sqrt(variance / (W * H)) + 1e-6
            for (i in 0 until W * H) f[3 * i + c] = ((f[3 * i + c] - mean) / sd).toFloat()
        }
        return f
    }

    /**
     * Las [keep] huellas de [all] más parecidas a esta, de mayor a menor similitud. Primero
     * una correlación rápida sin desplazamientos para descartar casi todas; luego la
     * comparación completa ([similarity]) con las [PRESELECT] mejores.
     */
    fun mostSimilar(all: Map<Int, ArtSignature>, keep: Int): List<Pair<Int, Double>> {
        val mine = features
        val buf = FloatArray(values.size)
        val quick = ArrayList<Pair<Int, Float>>(all.size)
        for ((id, other) in all) {
            val f = other.normalized(buf)
            var dot = 0f
            for (i in f.indices) dot += mine[i] * f[i]
            quick.add(id to dot)
        }
        quick.sortByDescending { it.second }
        return quick.take(PRESELECT).map { (id, _) -> id to similarity(all.getValue(id)) }
            .sortedByDescending { it.second }.take(keep)
    }

    /** Correlación máxima entre −1 y 1 (1 = idénticas salvo brillo, contraste y tinte). */
    fun similarity(other: ArtSignature): Double {
        var best = -1.0
        for (dy in -SHIFT..SHIFT) for (dx in -SHIFT..SHIFT) {
            best = maxOf(best, correlation(features, other.features, dx, dy))
        }
        return best
    }

    /** Correlación de la zona común cuando [a] se desplaza (dx, dy) celdas respecto a [b]. */
    private fun correlation(a: FloatArray, b: FloatArray, dx: Int, dy: Int): Double {
        val x0 = maxOf(0, dx); val x1 = W + minOf(0, dx)
        val y0 = maxOf(0, dy); val y1 = H + minOf(0, dy)
        var n = 0
        var sa = 0.0; var sb = 0.0
        for (y in y0 until y1) for (x in x0 until x1) for (c in 0 until 3) {
            sa += a[idx(x, y, c)]; sb += b[idx(x - dx, y - dy, c)]; n++
        }
        val ma = sa / n; val mb = sb / n
        var cov = 0.0; var va = 0.0; var vb = 0.0
        for (y in y0 until y1) for (x in x0 until x1) for (c in 0 until 3) {
            val da = a[idx(x, y, c)] - ma
            val db = b[idx(x - dx, y - dy, c)] - mb
            cov += da * db; va += da * da; vb += db * db
        }
        return if (va == 0.0 || vb == 0.0) 0.0 else cov / sqrt(va * vb)
    }

    companion object {
        const val W = 16
        const val H = 12
        private const val SHIFT = 1
        private const val PRESELECT = 60
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
