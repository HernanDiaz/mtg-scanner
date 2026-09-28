package com.hernan.mtgscanner

import com.hernan.mtgscanner.data.CollectionBackup
import com.hernan.mtgscanner.data.CollectionExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CollectionBackupTest {
    private val bolt = CollectionExport.Line("id1", "Lightning Bolt", "m11", "Magic 2011", "149", false, "NM", "es", 2, 1.5)

    /** La copia automática no se repite solo porque cambien los precios. */
    @Test fun fingerprintIgnoresPrices() =
        assertEquals(CollectionBackup.fingerprint(listOf(bolt)), CollectionBackup.fingerprint(listOf(bolt.copy(unitPrice = 3.0))))

    @Test fun fingerprintChangesWithTheCollection() {
        val base = CollectionBackup.fingerprint(listOf(bolt))
        assertNotEquals(base, CollectionBackup.fingerprint(listOf(bolt.copy(quantity = 3))))
        assertNotEquals(base, CollectionBackup.fingerprint(listOf(bolt.copy(condition = "EX"))))
        assertNotEquals(base, CollectionBackup.fingerprint(listOf(bolt.copy(deck = "Burn"))))
        assertNotEquals(base, CollectionBackup.fingerprint(emptyList()))
    }
}
