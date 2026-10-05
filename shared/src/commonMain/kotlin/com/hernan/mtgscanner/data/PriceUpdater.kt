package com.hernan.mtgscanner.data

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** La guía de precios de Cardmarket (en Android, ver CardmarketPrices). */
interface CardmarketGuideSource {
    /** Precios de los productos pedidos (descarga la guía si ha cambiado). */
    suspend fun pricesFor(ids: Set<Int>): Map<Int, CardmarketGuide>
}

/**
 * Actualiza los precios de las cartas de la colección con el endpoint
 * /cards/collection de Scryfall (75 cartas por petición) y, si se pide, con la
 * guía de precios de Cardmarket (precio más bajo y media de 30 días).
 */
class PriceUpdater(private val store: CollectionStore, private val cardmarket: CardmarketGuideSource? = null) {

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
        // Los precios de hoy, para ver después qué cartas han subido o bajado.
        store.recordPrices(CollectionStore.day())
        updated
    }

    private suspend fun fetch(ids: List<String>): Map<String, Prices> {
        val body = buildJsonObject {
            putJsonArray("identifiers") { ids.forEach { add(buildJsonObject { put("id", it) }) } }
        }
        val response = http.post("https://api.scryfall.com/cards/collection") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (response.status.value != 200) error("Scryfall respondió ${response.status.value}")
        return parse(response.bodyAsText())
    }

    companion object {
        private const val BATCH = 75
        private const val REQUEST_GAP_MS = 120L

        /** Precios de la respuesta de /cards/collection: id -> precios. */
        fun parse(text: String): Map<String, Prices> {
            val data = Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
            return data.associate { el ->
                val card = el.jsonObject
                val p = card["prices"]?.jsonObject ?: JsonObject(emptyMap())
                fun price(key: String) = (p[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.toDoubleOrNull()
                (card["id"] as JsonPrimitive).content to Prices(
                    eur = price("eur"), eurFoil = price("eur_foil"), usd = price("usd"), usdFoil = price("usd_foil"),
                )
            }
        }
    }
}
