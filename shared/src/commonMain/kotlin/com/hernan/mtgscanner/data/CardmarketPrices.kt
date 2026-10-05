package com.hernan.mtgscanner.data

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

/** La guía de precios de Cardmarket en [cacheDir] (la caché de la app). */
fun openCardmarketGuide(cacheDir: String): CardmarketGuideSource = CardmarketPrices(cacheDir)

/**
 * Guía de precios de Cardmarket: un fichero público (~26 MB, se actualiza una
 * vez al día) con los precios de todos los productos de Magic. Se guarda en la
 * caché y solo se vuelve a descargar si ha cambiado (ETag).
 */
class CardmarketPrices(cacheDir: String) : CardmarketGuideSource {
    private val fs = FileSystem.SYSTEM
    private val dir = cacheDir.toPath()
    private val file = dir / "cardmarket_price_guide.json"
    private val etagFile = dir / "cardmarket_price_guide.etag"

    /** Precios de los productos pedidos. Descarga la guía si ha cambiado desde la última vez. */
    override suspend fun pricesFor(ids: Set<Int>): Map<Int, CardmarketGuide> {
        if (ids.isEmpty()) return emptyMap()
        download()
        return withContext(Dispatchers.IO) { fs.source(file).buffer().use { parse(it, ids) } }
    }

    private suspend fun download() {
        val etag = if (fs.exists(file) && fs.exists(etagFile)) fs.read(etagFile) { readUtf8() } else null
        http.prepareGet(URL_GUIDE) { etag?.let { header("If-None-Match", it) } }.execute { response ->
            when (response.status.value) {
                304 -> return@execute
                200 -> withContext(Dispatchers.IO) {
                    fs.createDirectories(dir)
                    val tmp = dir / "cardmarket_price_guide.json.part"
                    val channel = response.bodyAsChannel()
                    fs.sink(tmp).buffer().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) out.write(buf, 0, n)
                        }
                    }
                    fs.atomicMove(tmp, file)
                    response.headers["ETag"]?.let { tag -> fs.write(etagFile) { writeUtf8(tag) } }
                }
                else -> error("Cardmarket respondió ${response.status.value}")
            }
        }
    }

    companion object {
        private const val URL_GUIDE = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_1.json"

        /**
         * Lee la guía sin cargarla entera en memoria y se queda con los productos pedidos.
         * Formato: {"priceGuides": [{"idProduct": 1, "trend": 0.07, "low": 0.02, "avg30": 0.09,
         * "trend-foil": ..., "low-foil": ..., "avg30-foil": ..., ...}, ...]}
         */
        fun parse(source: BufferedSource, ids: Set<Int>): Map<Int, CardmarketGuide> {
            val result = HashMap<Int, CardmarketGuide>()
            val r = JsonStream(source)
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "priceGuides") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var id = -1
                    val v = HashMap<String, Double>()
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        when {
                            name == "idProduct" -> id = r.nextNumber()?.toInt() ?: -1
                            name in FIELDS -> r.nextNumber()?.let { v[name] = it }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id in ids) {
                        result[id] = CardmarketGuide(
                            trend = v["trend"], trendFoil = v["trend-foil"],
                            low = v["low"], lowFoil = v["low-foil"],
                            avg30 = v["avg30"], avg30Foil = v["avg30-foil"],
                        ).positive()
                    }
                }
                r.endArray()
            }
            r.endObject()
            return result
        }

        private val FIELDS = setOf("trend", "trend-foil", "low", "low-foil", "avg30", "avg30-foil")

        /** Cardmarket pone 0 cuando no hay datos: mejor sin precio que gratis. */
        private fun CardmarketGuide.positive() = CardmarketGuide(
            trend?.takeIf { it > 0 }, trendFoil?.takeIf { it > 0 }, low?.takeIf { it > 0 },
            lowFoil?.takeIf { it > 0 }, avg30?.takeIf { it > 0 }, avg30Foil?.takeIf { it > 0 },
        )
    }
}

/**
 * Lector de JSON por partes, con lo justo para la guía de Cardmarket: objetos, listas,
 * nombres, números, y saltar cualquier otro valor. No carga el fichero en memoria.
 */
internal class JsonStream(private val src: BufferedSource) {
    /** Tras un valor dentro de un objeto o lista puede venir una coma. */
    private var afterValue = false

    private fun peekNonSpace(): Char {
        while (true) {
            val c = src.buffer.let { if (it.size == 0L && !src.request(1)) error("JSON incompleto") else it[0].toInt().toChar() }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || (c == ',' && afterValue)) { src.readByte(); if (c == ',') afterValue = false; continue }
            return c
        }
    }

    private fun expect(c: Char) {
        val got = peekNonSpace()
        if (got != c) error("JSON: se esperaba '$c' y hay '$got'")
        src.readByte()
    }

    fun beginObject() { expect('{'); afterValue = false }
    fun endObject() { expect('}'); afterValue = true }
    fun beginArray() { expect('['); afterValue = false }
    fun endArray() { expect(']'); afterValue = true }

    fun hasNext(): Boolean { val c = peekNonSpace(); return c != '}' && c != ']' }

    fun nextName(): String {
        val name = readString()
        expect(':')
        afterValue = false
        return name
    }

    /** El número siguiente; null (y se salta) si es otra cosa. */
    fun nextNumber(): Double? {
        val c = peekNonSpace()
        if (c != '-' && c !in '0'..'9') { skipValue(); return null }
        val sb = StringBuilder()
        while (src.request(1)) {
            val ch = src.buffer[0].toInt().toChar()
            if (ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') { sb.append(ch); src.readByte() } else break
        }
        afterValue = true
        return sb.toString().toDoubleOrNull()
    }

    fun skipValue() {
        when (peekNonSpace()) {
            '{' -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            '[' -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            '"' -> { readString(); afterValue = true }
            else -> {
                // número, true, false o null
                while (src.request(1)) {
                    val ch = src.buffer[0].toInt().toChar()
                    if (ch == ',' || ch == '}' || ch == ']' || ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') break
                    src.readByte()
                }
                afterValue = true
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val b = src.readByte().toInt().toChar()
            when (b) {
                '"' -> return sb.toString()
                '\\' -> {
                    when (val e = src.readByte().toInt().toChar()) {
                        'u' -> sb.append(src.readUtf8(4).toInt(16).toChar())
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(b) // los nombres de la guía son ASCII
            }
        }
    }
}
