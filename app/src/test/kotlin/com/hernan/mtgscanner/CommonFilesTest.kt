package com.hernan.mtgscanner

import com.hernan.mtgscanner.data.CardmarketPrices
import com.hernan.mtgscanner.data.ZipWriter
import okio.Buffer
import okio.buffer
import okio.sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** El zip y la guía de Cardmarket comunes (Android e iPhone), comprobados con las librerías de Java. */
class CommonFilesTest {

    @Test fun zipOpensWithJava() {
        val out = ByteArrayOutputStream()
        ZipWriter(out.sink().buffer()).use { zip ->
            zip.add("coleccion.csv", "Cantidad,Nombre\n1,Relámpago\n".encodeToByteArray())
            zip.add("fotos/Lightning Bolt (M11 149) 7.jpg", ByteArray(5000) { (it % 251).toByte() })
        }
        val seen = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                seen[e.name] = z.readBytes() // comprueba también el CRC
            }
        }
        assertEquals(listOf("coleccion.csv", "fotos/Lightning Bolt (M11 149) 7.jpg"), seen.keys.toList())
        assertEquals("Cantidad,Nombre\n1,Relámpago\n", seen.getValue("coleccion.csv").decodeToString())
        assertEquals(5000, seen.getValue("fotos/Lightning Bolt (M11 149) 7.jpg").size)
    }

    @Test fun cardmarketGuideKeepsOnlyRequestedProducts() {
        val json = """{"version":1,"createdAt":"2026-10-05T02:00:00+0200","priceGuides":[
            {"idProduct":1,"idCategory":1,"avg":0.1,"low":0.02,"trend":0.07,"avg1":null,"avg7":0.08,"avg30":0.09,
             "avg-foil":0,"low-foil":0,"trend-foil":0.5,"avg1-foil":0,"avg7-foil":0,"avg30-foil":0},
            {"idProduct":2,"trend":1.5,"low":1.0,"avg30":1.2,"note":"x\"y"},
            {"idProduct":3,"trend":9.9}]}"""
        val r = CardmarketPrices.parse(Buffer().writeUtf8(json), setOf(1, 3))
        assertEquals(setOf(1, 3), r.keys)
        assertEquals(0.07, r.getValue(1).trend!!, 1e-9)
        assertEquals(0.5, r.getValue(1).trendFoil!!, 1e-9)
        assertNull(r.getValue(1).lowFoil) // 0 = sin datos
        assertEquals(0.09, r.getValue(1).avg30!!, 1e-9)
        assertEquals(9.9, r.getValue(3).trend!!, 1e-9)
    }
}
