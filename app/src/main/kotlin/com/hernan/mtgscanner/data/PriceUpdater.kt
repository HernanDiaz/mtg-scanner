package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualiza los precios de las cartas de la colección con el endpoint
 * /cards/collection de Scryfall (75 cartas por petición) y, si se pide, con la
 * guía de precios de Cardmarket (precio más bajo y media de 30 días).
 */
class PriceUpdater(private val store: CollectionStore, private val cardmarket: CardmarketPrices? = null) {

    /** Devuelve cuántas impresiones se actualizaron. */
    suspend fun updateAll(withCardmarketGuide: Boolean = false): Int = withContext(Dispatchers.IO) {
        var updated = 0
        for (batch in store.distinctCardIds().chunked(BATCH)) {
            val prices = fetch(batch)
            store.updatePrices(prices)
            updated += prices.size
            delay(REQUEST_GAP_MS) // Scryfall pide no superar ~10 peticiones/s
        }
        if (withCardmarketGuide && cardmarket != null) {
            store.updateCardmarketPrices(cardmarket.pricesFor(store.cardmarketIds()))
        }
        updated
    }

    private fun fetch(ids: List<String>): Map<String, Prices> {
        val body = JSONObject().put("identifiers", JSONArray().apply {
            ids.forEach { put(JSONObject().put("id", it)) }
        }).toString()

        val conn = URL("https://api.scryfall.com/cards/collection").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", App.USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) error("Scryfall respondió ${conn.responseCode}")

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val data = json.getJSONArray("data")
            return buildMap {
                for (i in 0 until data.length()) {
                    val card = data.getJSONObject(i)
                    val p = card.getJSONObject("prices")
                    put(card.getString("id"), Prices(
                        eur = p.priceOrNull("eur"),
                        eurFoil = p.priceOrNull("eur_foil"),
                        usd = p.priceOrNull("usd"),
                        usdFoil = p.priceOrNull("usd_foil"),
                    ))
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.priceOrNull(key: String): Double? =
        if (isNull(key)) null else optString(key).toDoubleOrNull()

    companion object {
        private const val BATCH = 75
        private const val REQUEST_GAP_MS = 120L
    }
}
