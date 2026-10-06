package com.hernan.mtgscanner.data


/**
 * Empareja texto leído por OCR con nombres de cartas, tolerando errores
 * ("Lightnlng Bo1t" -> "Lightning Bolt"). Todo en memoria: ~35k nombres.
 */
class NameMatcher(entries: List<Pair<String, String>>) {

    /**
     * [names] tiene varios elementos cuando distintos nombres se normalizan
     * igual ("Wasteland" / "Waste Land"); decide la línea de coleccionista.
     */
    /** [key]: el nombre del catálogo (normalizado) con el que coincidió; puede ser un alias en otro idioma. */
    data class Match(val names: List<String>, val distance: Int, val length: Int, val key: String) {
        val score get() = length - 3 * distance
    }

    private val exact = HashMap<String, MutableList<String>>(entries.size * 2)
    private val byLength: Array<MutableList<String>>

    init {
        var maxLen = 0
        for ((norm, name) in entries) {
            val list = exact.getOrPut(norm) { mutableListOf() }
            if (name !in list) list.add(name)
            if (norm.length > maxLen) maxLen = norm.length
        }
        byLength = Array(maxLen + 1) { mutableListOf() }
        for (norm in exact.keys) byLength[norm.length].add(norm)
    }

    /**
     * Prueba la línea completa y sus prefijos de palabras (los símbolos de maná
     * al final del título suelen leerse como letras sueltas: "... R") y se queda
     * con la que explica más texto: puntuación = letras - 3 × errores. Así
     * "Delver of Sccrcts" no acaba en "Deliver" por el prefijo "Delver", ni
     * "Reclamation Saqe" en la carta "Reclamation".
     * Un nombre muy corto ("When", "Mind") solo cuenta si es la línea entera:
     * como prefijo aparece en cualquier frase ("When ... enters").
     */
    fun matchWords(words: List<String>): Match? {
        var best: Match? = null
        for (k in words.size downTo 1) {
            val m = match(words.subList(0, k).joinToString(" ")) ?: continue
            if (k < words.size && m.key.length <= SHORT_NAME) continue
            if (best == null || m.score > best.score) best = m
        }
        return best
    }

    fun match(raw: String): Match? {
        val q = normalize(raw)
        if (q.length < 3) return null
        exact[q]?.let { return Match(it, 0, q.length, q) }
        // Nombres cortos solo por coincidencia exacta: con 1 error "Shoek" podría ser muchas cosas.
        if (q.length < 6) return null
        val (norm, dist) = nearest(q, maxOf(1, q.length / 5)).firstOrNull() ?: return null
        return Match(exact.getValue(norm), dist, q.length, norm)
    }

    /**
     * Para lecturas muy dañadas ("Nodalian Viystio" -> "Vodalian Mystic"):
     * admite hasta un tercio de errores, pero solo si el mejor candidato
     * destaca claramente sobre el segundo.
     */
    fun matchRelaxed(raw: String): Match? {
        val q = normalize(raw)
        if (q.length < 8) return null
        val found = nearest(q, q.length / 3, keep = 2)
        val first = found.firstOrNull() ?: return null
        val second = found.getOrNull(1)
        if (second != null && second.second - first.second < 2) return null
        return Match(exact.getValue(first.first), first.second, q.length, first.first)
    }

    /**
     * Candidatos para un nombre muy mal leído ("Mox Derl", "Blackk Lutob"): los [keep] más
     * cercanos admitiendo hasta ~45 % de errores. No sirven solos: hay que confirmarlos con
     * otra pista (la ilustración, ver FrameRecognizer).
     */
    fun closest(raw: String, keep: Int): List<Match> {
        val q = normalize(raw)
        if (q.length < 5) return emptyList()
        return nearest(q, maxOf(2, q.length * 45 / 100), keep).map { (norm, d) -> Match(exact.getValue(norm), d, q.length, norm) }
    }

    /**
     * Los [keep] nombres normalizados más cercanos con distancia <= [maxDist]. A igual
     * distancia, el de longitud más parecida: el OCR suele cambiar una letra por otra
     * ("Ierror" es "Terror", no "Error").
     */
    private fun nearest(q: String, maxDist: Int, keep: Int = 1): List<Pair<String, Int>> =
        // Con la carta quieta el OCR lee lo mismo fotograma tras fotograma: se recuerda.
        recent.getOrPut("$q|$maxDist|$keep") {
            if (recent.size() > RECENT_MAX) recent.clear()
            nearestUncached(q, maxDist, keep)
        }

    private val recent = ConcurrentCache<String, List<Pair<String, Int>>>()

    private fun nearestUncached(q: String, maxDist: Int, keep: Int): List<Pair<String, Int>> {
        val best = ArrayList<Pair<String, Int>>(keep + 1)
        var limit = maxDist
        val lo = maxOf(0, q.length - maxDist)
        val hi = minOf(byLength.size - 1, q.length + maxDist)
        val rowA = IntArray(hi + 1)
        val rowB = IntArray(hi + 1)
        for (len in lo..hi) {
            for (cand in byLength[len]) {
                val d = boundedLevenshtein(q, cand, limit, rowA, rowB)
                if (d > limit) continue
                best.add(cand to d)
                best.sortWith(compareBy({ it.second }, { kotlin.math.abs(it.first.length - q.length) }))
                if (best.size > keep) best.removeAt(best.size - 1)
                if (best.size == keep) limit = best.last().second
            }
        }
        return best
    }

    /** Búsqueda manual: nombres que contienen el texto, los que empiezan por él primero. */
    fun search(query: String, limit: Int = 30): List<String> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val starts = mutableListOf<String>()
        val contains = mutableListOf<String>()
        for ((norm, names) in exact) {
            when {
                norm.startsWith(q) -> starts.addAll(names)
                norm.contains(q) -> contains.addAll(names)
            }
        }
        return (starts.sorted() + contains.sorted()).distinct().take(limit)
    }

    companion object {
        private const val SHORT_NAME = 4
        /** Búsquedas recordadas: de sobra para las líneas de unos cuantos fotogramas. */
        private const val RECENT_MAX = 2000

        /** Debe coincidir con norm_name() de tools/build_catalog.py. */
        fun normalize(s: String): String {
            val nfkd = nfkd(s)
            val sb = StringBuilder(nfkd.length)
            for (c in nfkd) {
                val l = c.lowercaseChar()
                if (l in 'a'..'z' || l in '0'..'9') sb.append(l)
            }
            return sb.toString()
        }

        /**
         * Levenshtein que abandona en cuanto supera [max]; devuelve max+1 en ese caso. Solo
         * calcula la banda |i - j| <= max (fuera de ella la distancia ya pasa de [max]) y no crea
         * nada por llamada si se le dan las filas ([rowA], [rowB], de al menos b.length + 1): se
         * llama cientos de miles de veces por fotograma, y en el iPhone (Kotlin/Native) crear dos
         * arrays cada vez era lo que más tardaba.
         */
        fun boundedLevenshtein(
            a: String, b: String, max: Int,
            rowA: IntArray = IntArray(b.length + 1), rowB: IntArray = IntArray(b.length + 1),
        ): Int {
            if (max < 0 || kotlin.math.abs(a.length - b.length) > max) return max + 1
            val over = max + 1
            var prev = rowA
            var cur = rowB
            for (j in 0..b.length) prev[j] = if (j <= max) j else over
            for (i in 1..a.length) {
                val from = maxOf(1, i - max)
                val to = minOf(b.length, i + max)
                cur[from - 1] = if (from == 1) minOf(i, over) else over
                var rowMin = cur[from - 1]
                val ai = a[i - 1]
                for (j in from..to) {
                    val cost = if (ai == b[j - 1]) 0 else 1
                    var v = prev[j - 1] + cost
                    val del = prev[j] + 1
                    if (del < v) v = del
                    val ins = cur[j - 1] + 1
                    if (ins < v) v = ins
                    if (v > over) v = over
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (to < b.length) cur[to + 1] = over
                if (rowMin > max) return over
                val t = prev; prev = cur; cur = t
            }
            return if (prev[b.length] > max) over else prev[b.length]
        }
    }
}
