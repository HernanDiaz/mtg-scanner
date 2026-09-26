package com.hernan.mtgscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.CollectionStore
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

        // Pantano pendiente: la "foto" es la ilustración de una edición antigua concreta,
        // así que el selector debería ponerla la primera.
        val swamps = catalog.printingsOf("Swamp")
        val photographed = swamps.last { it.artId != null && catalog.art(it.artId!!) != null }
        val photo = catalog.art(photographed.artId!!)!!.toPacked()
        repeat(2) { store.add(swamps.first(), false, "NM", needsReview = true, lang = "es", art = photo) }
        store.add(catalog.printingsOf("Lightning Bolt")[3], false, "NM", needsReview = true)
        store.close()
        println("Pantano fotografiado: ${photographed.setCode} ${photographed.collectorNumber}")
    }
}
