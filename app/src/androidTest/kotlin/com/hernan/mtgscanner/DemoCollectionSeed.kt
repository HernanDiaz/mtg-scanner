package com.hernan.mtgscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.CollectionFilter
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.PriceKind
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rellena la colección REAL del dispositivo con cartas de ejemplo, para revisar
 * la interfaz a mano. Solo se ejecuta si se pide: -e seed true
 */
@RunWith(AndroidJUnit4::class)
class DemoCollectionSeed {
    @Test fun seed() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seed") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("collection.db")
        val store = CollectionStore(context)
        val catalog = Catalog(context)

        fun first(name: String) = catalog.printingsOf(name).first { it.prices.eur != null }
        store.add(first("Lightning Bolt"), false, "NM", needsReview = false)
        store.add(first("Counterspell"), false, "NM", needsReview = false, lang = "es")
        store.add(first("Sol Ring"), true, "NM", needsReview = false)
        repeat(3) { store.add(first("Llanowar Elves"), false, "NM", needsReview = false) }
        listOf(
            "Dark Ritual", "Swords to Plowshares", "Brainstorm", "Birds of Paradise", "Serra Angel",
            "Shivan Dragon", "Giant Growth", "Wrath of God", "Thoughtseize", "Cultivate",
            "Arcane Signet", "Command Tower", "Rhystic Study", "Smothering Tithe", "Cyclonic Rift",
        ).forEachIndexed { i, name ->
            store.add(first(name), foil = i % 5 == 0, condition = "NM", needsReview = false,
                lang = if (i % 3 == 0) "es" else "en")
        }

        // Pantano pendiente: la "foto" es la ilustración de una edición antigua concreta,
        // así que el selector debería ponerla la primera.
        val swamps = catalog.printingsOf("Swamp")
        val photographed = swamps.last { it.artId != null && catalog.art(it.artId!!) != null }
        val photo = catalog.art(photographed.artId!!)!!.toPacked()
        repeat(2) { store.add(swamps.first(), false, "NM", needsReview = true, lang = "es", art = photo) }
        store.add(catalog.printingsOf("Lightning Bolt")[3], false, "NM", needsReview = true)

        // Un par de mazos de ejemplo
        val red = store.createDeck("Rojo agresivo")
        listOf("Lightning Bolt", "Shivan Dragon").forEach { name ->
            store.groups(Currency.EUR, CollectionFilter(query = name)).filter { !it.needsReview }
                .forEach { store.moveToDeck(it, red, it.quantity) }
        }
        val commander = store.createDeck("Commander")
        listOf("Sol Ring", "Arcane Signet", "Command Tower", "Rhystic Study", "Smothering Tithe", "Cyclonic Rift").forEach { name ->
            store.groups(Currency.EUR, CollectionFilter(query = name)).forEach { store.moveToDeck(it, commander, it.quantity) }
        }
        store.close()
        println("Pantano fotografiado: ${photographed.setCode} ${photographed.collectorNumber}")
    }

    /**
     * Historia INVENTADA de 60 días para la colección que haya en el dispositivo, para
     * ver la gráfica de evolución sin esperar semanas. Solo se ejecuta si se pide: -e history true
     */
    @Test fun seedHistory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("history") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CollectionStore(context)
        val db = store.writableDatabase
        val rnd = java.util.Random(7)
        val cards = store.summary(Currency.EUR).cards
        // Un paseo aleatorio que acaba en el valor de hoy, para cada precio.
        val today = listOf(
            store.summary(Currency.EUR).total, store.summary(Currency.EUR, kind = PriceKind.LOW).total,
            store.summary(Currency.EUR, kind = PriceKind.AVG30).total, store.summary(Currency.USD).total,
        )
        val walk = DoubleArray(61).also { w ->
            w[0] = 1.0
            for (i in 1..60) w[i] = w[i - 1] / (1 + (rnd.nextGaussian() * 0.012 + 0.0018))
        }
        for (d in 60 downTo 1) {
            val day = CollectionStore.day(System.currentTimeMillis() - d * 86_400_000L)
            db.execSQL("INSERT OR REPLACE INTO value_history VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(day, cards - d / 20, today[0] * walk[d], today[1] * walk[d], today[2] * walk[d], today[3] * walk[d]))
        }
        // Precios de hace 30 días: los actuales con una variación de hasta ±25 %.
        val past = CollectionStore.day(System.currentTimeMillis() - 30 * 86_400_000L)
        val cols = listOf("eur", "eur_foil", "eur_low", "eur_low_foil", "eur_avg30", "eur_avg30_foil", "usd", "usd_foil")
        db.rawQuery("SELECT card_id, ${cols.joinToString()} FROM prices", null).use { c ->
            while (c.moveToNext()) {
                val f = 1 + (rnd.nextDouble() - 0.5) * 0.5
                val values = cols.indices.map { i -> if (c.isNull(i + 1)) null else c.getDouble(i + 1) * f }
                db.execSQL("INSERT OR REPLACE INTO price_history (card_id, day, ${cols.joinToString()}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(c.getString(0), past, *values.toTypedArray()))
            }
        }
        store.recordPrices(CollectionStore.day())
        store.close()
    }

    /** Borra la historia (la inventada por [seedHistory]) salvo la de hoy: -e clearHistory true */
    @Test fun clearHistory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clearHistory") == "true")
        val store = CollectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val today = CollectionStore.day()
        store.writableDatabase.delete("value_history", "day < ?", arrayOf(today))
        store.writableDatabase.delete("price_history", "day < ?", arrayOf(today))
        store.close()
    }
}
