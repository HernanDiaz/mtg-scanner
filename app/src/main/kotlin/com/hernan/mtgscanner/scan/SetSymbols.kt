package com.hernan.mtgscanner.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Símbolo de edición tal como se imprime (con el color de la rareza), recortado
 * de una carta real con la carta a [SetSymbols.CARD_W] px de ancho. Lo genera
 * tools/build_set_symbols.py. [rgb]: valores 0-1, fila a fila, 3 por píxel.
 */
class SymbolRef(val w: Int, val h: Int, val rgb: FloatArray) {
    companion object {
        fun fromBytes(w: Int, h: Int, bytes: ByteArray) =
            SymbolRef(w, h, FloatArray(w * h * 3) { (bytes[it].toInt() and 0xFF) / 255f })
    }
}

/** La zona de la foto donde debería estar el símbolo, a la misma escala que las referencias. */
class SymbolWindow(val w: Int, val h: Int, val rgb: FloatArray)

/**
 * Los símbolos de referencia y cómo compararlos con la foto (correlación
 * normalizada en color, buscando la posición y probando varios tamaños).
 */
class SetSymbols(
    private val refs: Map<String, SymbolRef>,
    /** Ediciones cuyas cartas no llevan símbolo (Alpha, Cuarta Edición...). */
    private val noSymbol: Set<String>,
    /** Ediciones que imprimen el símbolo de otra (Chronicles, Salvat...). */
    private val otherSymbol: Set<String>,
    /** Icono de cada edición: las que comparten icono (una edición y sus promociones) no se distinguen. */
    private val icons: Map<String, String> = emptyMap(),
) {
    enum class Kind { OWN, NONE, OTHER, UNKNOWN }

    /** Referencia del símbolo de una edición, rareza y estilo de impresión (CardPrinting.style). */
    fun ref(set: String, rarity: String?, style: String): SymbolRef? = refs[key(set, rarity, style)]

    fun kind(set: String, rarity: String?, style: String): Kind = when {
        set in noSymbol -> Kind.NONE
        set in otherSymbol -> Kind.OTHER
        ref(set, rarity, style) != null -> Kind.OWN
        else -> Kind.UNKNOWN
    }

    fun sameIcon(a: String, b: String): Boolean = a == b || (icons[a] != null && icons[a] == icons[b])

    private val twinCache = java.util.concurrent.ConcurrentHashMap<Pair<SymbolRef, SymbolRef>, Double>()

    /** Parecido entre dos referencias: si es alto, la foto no puede distinguirlas. */
    fun similarity(a: SymbolRef, b: SymbolRef): Double = twinCache.getOrPut(a to b) {
        val pad = 4
        val w = a.w + 2 * pad
        val h = a.h + 2 * pad
        // Relleno con el color medio del borde de a, para que el marco no cuente.
        val fill = FloatArray(3) { c -> (0 until a.w).map { a.rgb[it * 3 + c] }.average().toFloat() }
        val rgb = FloatArray(w * h * 3) { fill[it % 3] }
        for (y in 0 until a.h) for (x in 0 until a.w) for (c in 0 until 3) {
            rgb[((y + pad) * w + x + pad) * 3 + c] = a.rgb[(y * a.w + x) * 3 + c]
        }
        score(SymbolWindow(w, h, rgb), b)
    }

    companion object {
        fun key(set: String, rarity: String?, style: String) = "$set|$rarity|$style"

        /** Ancho de la carta, en px, al que están las referencias y se remuestrea la foto. */
        const val CARD_W = 400
        private val SCALES = floatArrayOf(0.85f, 0.92f, 1f, 1.08f, 1.17f)

        /**
         * Mejor correlación (−1..1) de [ref] dentro de [win], probando posiciones y
         * tamaños: primero de dos en dos píxeles y luego afinando alrededor del mejor.
         */
        fun score(win: SymbolWindow, ref: SymbolRef): Double {
            val integral = Integral(win)
            var best = -1.0
            var bestScale = -1
            var bestX = 0
            var bestY = 0
            val templates = SCALES.map { resize(ref, it) }
            for ((i, t) in templates.withIndex()) {
                if (t == null || t.w > win.w || t.h > win.h) continue
                var y = 0
                while (y <= win.h - t.h) {
                    var x = 0
                    while (x <= win.w - t.w) {
                        val v = ncc(win, integral, t, x, y)
                        if (v > best) { best = v; bestScale = i; bestX = x; bestY = y }
                        x += 2
                    }
                    y += 2
                }
            }
            if (bestScale < 0) return best
            val t = templates[bestScale]!!
            for (y in max(0, bestY - 1)..min(win.h - t.h, bestY + 1)) {
                for (x in max(0, bestX - 1)..min(win.w - t.w, bestX + 1)) {
                    best = max(best, ncc(win, integral, t, x, y))
                }
            }
            return best
        }

        private val LOCATE_SCALES = floatArrayOf(0.88f, 1f, 1.12f)

        /**
         * Dónde está el símbolo en la ventana (su centro, en px de la ventana). Se busca
         * a media resolución con unas pocas referencias: el símbolo de la foto es uno
         * solo, así que después basta comparar cada candidata alrededor de ese punto
         * ([scoreNear]) en vez de recorrer toda la ventana con cada una.
         */
        fun locate(win: SymbolWindow, refs: List<SymbolRef>): Pair<Float, Float>? {
            val half = halve(win)
            val integral = Integral(half)
            var best = -1.0
            var center: Pair<Float, Float>? = null
            for (ref in refs) for (s in LOCATE_SCALES) {
                val t = resize(ref, s * 0.5f) ?: continue
                if (t.w > half.w || t.h > half.h) continue
                for (y in 0..half.h - t.h) for (x in 0..half.w - t.w) {
                    val v = ncc(half, integral, t, x, y)
                    if (v > best) {
                        best = v
                        center = (x + t.w / 2f) * 2 to (y + t.h / 2f) * 2
                    }
                }
            }
            return center
        }

        /** Mejor correlación de [ref] con su centro a menos de [radius] px de ([cx], [cy]). */
        fun scoreNear(win: SymbolWindow, ref: SymbolRef, cx: Float, cy: Float, radius: Int = 4): Double {
            val integral = Integral(win)
            var best = -1.0
            for (s in SCALES) {
                val t = resize(ref, s) ?: continue
                if (t.w > win.w || t.h > win.h) continue
                val x0 = (cx - t.w / 2f).roundToInt()
                val y0 = (cy - t.h / 2f).roundToInt()
                for (y in max(0, y0 - radius)..min(win.h - t.h, y0 + radius)) {
                    for (x in max(0, x0 - radius)..min(win.w - t.w, x0 + radius)) {
                        best = max(best, ncc(win, integral, t, x, y))
                    }
                }
            }
            return best
        }

        /** La ventana a la mitad de tamaño (media de 2×2 píxeles). */
        private fun halve(win: SymbolWindow): SymbolWindow {
            val w = win.w / 2
            val h = win.h / 2
            val rgb = FloatArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
                fun p(dx: Int, dy: Int) = win.rgb[((2 * y + dy) * win.w + 2 * x + dx) * 3 + c]
                rgb[(y * w + x) * 3 + c] = (p(0, 0) + p(1, 0) + p(0, 1) + p(1, 1)) / 4
            }
            return SymbolWindow(w, h, rgb)
        }

        /** Plantilla centrada (media 0 por canal) y su norma. */
        private class Template(val w: Int, val h: Int, val v: FloatArray, val norm: Double)

        private fun resize(ref: SymbolRef, scale: Float): Template? {
            val w = (ref.w * scale).roundToInt()
            val h = (ref.h * scale).roundToInt()
            if (w < 4 || h < 4) return null
            val v = FloatArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) {
                // Bilineal
                val sx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, ref.w - 1f)
                val sy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, ref.h - 1f)
                val x0 = sx.toInt(); val y0 = sy.toInt()
                val x1 = min(x0 + 1, ref.w - 1); val y1 = min(y0 + 1, ref.h - 1)
                val fx = sx - x0; val fy = sy - y0
                for (c in 0 until 3) {
                    val a = ref.rgb[(y0 * ref.w + x0) * 3 + c] * (1 - fx) + ref.rgb[(y0 * ref.w + x1) * 3 + c] * fx
                    val b = ref.rgb[(y1 * ref.w + x0) * 3 + c] * (1 - fx) + ref.rgb[(y1 * ref.w + x1) * 3 + c] * fx
                    v[(y * w + x) * 3 + c] = a * (1 - fy) + b * fy
                }
            }
            val n = w * h
            for (c in 0 until 3) {
                var mean = 0.0
                for (i in 0 until n) mean += v[i * 3 + c]
                mean /= n
                for (i in 0 until n) v[i * 3 + c] -= mean.toFloat()
            }
            var sq = 0.0
            for (f in v) sq += f * f
            return if (sq < 1e-6) null else Template(w, h, v, sqrt(sq))
        }

        /** Sumas acumuladas de la ventana (por canal, valores y cuadrados) para medias rápidas. */
        private class Integral(win: SymbolWindow) {
            val stride = win.w + 1
            val sum = DoubleArray(stride * (win.h + 1) * 3)
            val sq = DoubleArray(stride * (win.h + 1) * 3)

            init {
                for (y in 0 until win.h) for (x in 0 until win.w) for (c in 0 until 3) {
                    val p = win.rgb[(y * win.w + x) * 3 + c].toDouble()
                    val i = ((y + 1) * stride + x + 1) * 3 + c
                    sum[i] = p + sum[i - 3] + sum[i - stride * 3] - sum[i - stride * 3 - 3]
                    sq[i] = p * p + sq[i - 3] + sq[i - stride * 3] - sq[i - stride * 3 - 3]
                }
            }

            fun box(a: DoubleArray, x: Int, y: Int, w: Int, h: Int, c: Int): Double =
                a[((y + h) * stride + x + w) * 3 + c] - a[(y * stride + x + w) * 3 + c] -
                    a[((y + h) * stride + x) * 3 + c] + a[(y * stride + x) * 3 + c]
        }

        private fun ncc(win: SymbolWindow, integral: Integral, t: Template, x: Int, y: Int): Double {
            val n = t.w * t.h
            var variance = 0.0
            for (c in 0 until 3) {
                val s = integral.box(integral.sum, x, y, t.w, t.h, c)
                variance += integral.box(integral.sq, x, y, t.w, t.h, c) - s * s / n
            }
            if (variance < 1e-6) return 0.0
            var num = 0.0
            for (ty in 0 until t.h) {
                var wi = ((y + ty) * win.w + x) * 3
                var ti = ty * t.w * 3
                for (k in 0 until t.w * 3) {
                    num += t.v[ti++] * win.rgb[wi++]
                }
            }
            return num / (t.norm * sqrt(variance))
        }
    }
}
