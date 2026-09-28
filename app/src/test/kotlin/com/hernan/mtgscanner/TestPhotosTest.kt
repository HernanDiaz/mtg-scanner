package com.hernan.mtgscanner

import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.TestPhotos
import org.junit.Assert.assertEquals
import org.junit.Test

class TestPhotosTest {
    private fun photo(file: String, entryId: Long?) =
        TestPhotos.Photo(file, entryId, "Lightning Bolt", "m11", "149", true, "en", 1080, 2200, 0)

    /** La respuesta correcta es la carta tal como está ahora (corregida), no lo que dijo la app. */
    @Test fun labelsUseTheCorrectedCard() {
        val now = mapOf(
            1L to CollectionStore.EntryNow("id-a25", "Lightning Bolt", "a25", "141", "es", needsReview = false),
            2L to CollectionStore.EntryNow("id-m11", "Lightning Bolt", "m11", "149", "en", needsReview = true),
        )
        val rows = TestPhotos.labels(
            listOf(photo("a.jpg", 1), photo("b.jpg", 2), photo("c.jpg", 3), photo("d.jpg", null)), now,
        ).trim().lines().map { it.split(",") }
        val header = rows[0]
        fun field(row: Int, name: String) = rows[row][header.indexOf(name)]

        assertEquals("id-a25", field(1, "card_id"))
        assertEquals("es", field(1, "lang"))
        assertEquals("m11", field(1, "app_set")) // lo que dijo la app
        assertEquals("movil", field(1, "group"))
        assertEquals("movil_sin_revisar", field(2, "group"))
        assertEquals("movil_borrada", field(3, "group"))
        assertEquals("", field(3, "card_id"))
        assertEquals("movil_manual", field(4, "group"))
        assertEquals("2200", field(1, "view_h"))
    }
}
