package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.App
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Lo que la ficha de una carta muestra y no está en el catálogo del móvil
 * (texto, legalidad, enlaces): se pide a Scryfall al abrirla y se recuerda
 * mientras la app está abierta.
 */
data class CardDetails(
    val faces: List<Face>,
    /** Formato -> "legal", "not_legal", "banned", "restricted". */
    val legalities: Map<String, String>,
    val cardmarketUrl: String?,
    val scryfallUrl: String?,
) {
    data class Face(
        val name: String,
        val manaCost: String?,
        val typeLine: String?,
        val oracleText: String?,
        /** Texto impreso en el idioma de la carta, si no es inglés. */
        val printedText: String?,
        val flavorText: String?,
        val power: String?,
        val toughness: String?,
        val loyalty: String?,
        val artist: String?,
    )

    companion object {
        private val cache = ConcurrentHashMap<String, CardDetails>()

        /** Formatos que se muestran, en este orden. */
        val FORMATS = listOf("standard", "pioneer", "modern", "legacy", "vintage", "commander", "pauper")

        /** Detalles de una impresión (id de Scryfall). Lanza una excepción sin conexión. */
        fun fetch(id: String): CardDetails = cache.getOrPut(id) {
            val conn = URL("https://api.scryfall.com/cards/$id").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", App.USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) error("Scryfall ${conn.responseCode}")
                parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
            } finally {
                conn.disconnect()
            }
        }

        fun parse(json: JSONObject): CardDetails {
            fun JSONObject.str(key: String) = if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotEmpty() } else null
            fun face(o: JSONObject, fallback: JSONObject) = Face(
                name = o.str("name") ?: fallback.str("name").orEmpty(),
                manaCost = o.str("mana_cost"),
                typeLine = o.str("type_line"),
                oracleText = o.str("oracle_text"),
                printedText = o.str("printed_text"),
                flavorText = o.str("flavor_text"),
                power = o.str("power"),
                toughness = o.str("toughness"),
                loyalty = o.str("loyalty"),
                artist = o.str("artist") ?: fallback.str("artist"),
            )
            val faces = json.optJSONArray("card_faces")?.let { arr ->
                (0 until arr.length()).map { face(arr.getJSONObject(it), json) }
            } ?: listOf(face(json, json))
            val legal = json.optJSONObject("legalities")?.let { l -> l.keys().asSequence().associateWith { l.getString(it) } }.orEmpty()
            return CardDetails(
                faces = faces,
                legalities = legal,
                cardmarketUrl = json.optJSONObject("purchase_uris")?.str("cardmarket"),
                scryfallUrl = json.str("scryfall_uri"),
            )
        }
    }
}
