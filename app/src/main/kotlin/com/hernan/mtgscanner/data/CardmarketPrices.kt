package com.hernan.mtgscanner.data

import android.util.JsonReader
import com.hernan.mtgscanner.App
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Guía de precios de Cardmarket: un fichero público (~26 MB, se actualiza una
 * vez al día) con los precios de todos los productos de Magic. Se guarda en la
 * caché y solo se vuelve a descargar si ha cambiado (ETag).
 */
class CardmarketPrices(private val cacheDir: File) {

    /** Precios de un producto, en euros. */
    data class Guide(
        val trend: Double? = null, val trendFoil: Double? = null,
        val low: Double? = null, val lowFoil: Double? = null,
        val avg30: Double? = null, val avg30Foil: Double? = null,
    )

    private val file get() = File(cacheDir, "cardmarket_price_guide.json")
    private val etagFile get() = File(cacheDir, "cardmarket_price_guide.etag")

    /** Precios de los productos pedidos. Descarga la guía si ha cambiado desde la última vez. */
    fun pricesFor(ids: Set<Int>): Map<Int, Guide> {
        if (ids.isEmpty()) return emptyMap()
        download()
        return file.inputStream().buffered().use { parse(it, ids) }
    }

    private fun download() {
        val conn = URL(URL_GUIDE).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", App.USER_AGENT)
            if (file.exists() && etagFile.exists()) conn.setRequestProperty("If-None-Match", etagFile.readText())
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return
                HttpURLConnection.HTTP_OK -> {
                    val tmp = File(cacheDir, file.name + ".part")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                    tmp.renameTo(file)
                    conn.getHeaderField("ETag")?.let { etagFile.writeText(it) }
                }
                else -> error("Cardmarket respondió ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val URL_GUIDE = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_1.json"

        /**
         * Lee la guía sin cargarla entera en memoria y se queda con los productos pedidos.
         * Formato: {"priceGuides": [{"idProduct": 1, "trend": 0.07, "low": 0.02, "avg30": 0.09,
         * "trend-foil": ..., "low-foil": ..., "avg30-foil": ..., ...}, ...]}
         */
        fun parse(input: InputStream, ids: Set<Int>): Map<Int, Guide> {
            val result = HashMap<Int, Guide>()
            JsonReader(input.reader()).use { r ->
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
                                name == "idProduct" -> id = r.nextInt()
                                name in FIELDS && r.peek() == android.util.JsonToken.NUMBER -> v[name] = r.nextDouble()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (id in ids) {
                            result[id] = Guide(
                                trend = v["trend"], trendFoil = v["trend-foil"],
                                low = v["low"], lowFoil = v["low-foil"],
                                avg30 = v["avg30"], avg30Foil = v["avg30-foil"],
                            ).positive()
                        }
                    }
                    r.endArray()
                }
                r.endObject()
            }
            return result
        }

        private val FIELDS = setOf("trend", "trend-foil", "low", "low-foil", "avg30", "avg30-foil")

        /** Cardmarket pone 0 cuando no hay datos: mejor sin precio que gratis. */
        private fun Guide.positive() = Guide(
            trend?.takeIf { it > 0 }, trendFoil?.takeIf { it > 0 }, low?.takeIf { it > 0 },
            lowFoil?.takeIf { it > 0 }, avg30?.takeIf { it > 0 }, avg30Foil?.takeIf { it > 0 },
        )
    }
}
