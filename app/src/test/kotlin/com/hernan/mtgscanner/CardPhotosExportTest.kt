package com.hernan.mtgscanner

import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionStore.EntryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPhotosExportTest {
    private val bolt = CollectionExport.Line("id1", "Lightning Bolt", "m11", "Magic 2011", "149", false, "NM", "es", 2, 1.5, deck = "Burn")
    private val bear = CollectionExport.Line("id2", "Grizzly Bears", "lea", "Alpha", "189", false, "NM", "en", 1, 0.5)

    private val entries = listOf(
        EntryKey(10, "id1", false, "NM", "es", 7, false),
        EntryKey(11, "id1", false, "NM", "es", 7, false),
        EntryKey(12, "id1", false, "NM", "es", null, false), // otra copia, sin mazo: otra línea
        EntryKey(20, "id2", false, "NM", "en", null, false),
    )

    /** Cada línea lleva las fotos de sus copias; las copias sin foto no cuentan. */
    @Test fun photosGoToTheirLines() {
        val (lines, names) = CollectionExport.withPhotos(listOf(bolt, bear), entries, detailed = true,
            deckName = { if (it == 7L) "Burn" else null }, hasPhoto = { it != 11L })
        assertEquals(listOf("Lightning Bolt (M11 149) 10.jpg"), lines[0].photos)
        assertEquals(listOf("Grizzly Bears (LEA 189) 20.jpg"), lines[1].photos)
        assertEquals(setOf(10L, 20L), names.keys)
    }

    /** La columna de fotos solo aparece si hay fotos: el CSV de siempre no cambia. */
    @Test fun photoColumnOnlyWithPhotos() {
        val plain = CollectionExport.csv(listOf(bolt), com.hernan.mtgscanner.data.Currency.EUR)
        assertFalse(plain.lineSequence().first().contains("Fotos"))
        val withPhotos = CollectionExport.csv(listOf(bolt.copy(photos = listOf("a.jpg", "b.jpg"))), com.hernan.mtgscanner.data.Currency.EUR)
        assertTrue(withPhotos.lineSequence().first().endsWith("Fotos"))
        assertTrue(withPhotos.lineSequence().drop(1).first().endsWith("fotos/a.jpg; fotos/b.jpg"))
    }
}
