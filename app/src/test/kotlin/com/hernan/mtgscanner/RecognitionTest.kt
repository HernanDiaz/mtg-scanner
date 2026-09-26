package com.hernan.mtgscanner

import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.NameMatcher
import com.hernan.mtgscanner.data.Prices
import com.hernan.mtgscanner.scan.CollectorInfo
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.FrameResult
import com.hernan.mtgscanner.scan.OcrLine
import com.hernan.mtgscanner.scan.ScanSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun card(
    name: String, set: String, number: String, released: String,
    promo: Boolean = false, eur: Double = 1.0, setSize: Int? = 250, setType: String = "expansion",
    lang: String = "en",
) = CardPrinting(
    id = "$set-$number", name = name, setCode = set, setName = set.uppercase(), setType = setType,
    setSize = setSize, collectorNumber = number, numberNorm = number.trimStart('0'), rarity = "c",
    releasedAt = released, finishes = 3, promo = promo, lang = lang, prices = Prices(eur, eur * 2, eur, eur * 2),
)

private val BOLTS = listOf( // ordenadas por fecha desc, como las devuelve el catálogo
    card("Lightning Bolt", "sld", "999", "2025-01-01", eur = 5.0, setType = "box"),
    card("Lightning Bolt", "clu", "141", "2024-02-23", eur = 0.5),
    card("Lightning Bolt", "2x2", "117", "2022-07-08", eur = 1.0, setSize = 332),
    card("Lightning Bolt", "m11", "149", "2010-07-16", eur = 1.2, setSize = 249),
    card("Lightning Bolt", "m10", "146", "2009-07-17", eur = 1.5, setSize = 249),
    card("Lightning Bolt", "lea", "161", "1993-08-05", eur = 400.0, setSize = null),
    card("Lightning Bolt", "leb", "162", "1993-10-04", eur = 250.0, setSize = null),
)
private val SHOCK = listOf(card("Shock", "m20", "163", "2019-07-12"))
private val WASTELAND = listOf(card("Wasteland", "ltc", "376", "2023-06-23"), card("Wasteland", "tmp", "330", "1997-10-14"))
private val WASTE_LAND = listOf(card("Waste Land", "cmb2", "121", "2021-01-01"))

private val SWAMPS = listOf(
    card("Swamp", "phuk", "58", "2006-01-01", setSize = 60, eur = 0.2),
    card("Swamp", "psal", "A58", "2005-01-01", setSize = 600, eur = 0.3, lang = "es"),
    card("Swamp", "psal", "F36", "2005-01-01", setSize = 600, eur = 0.3, lang = "es"),
    card("Swamp", "m20", "276", "2019-07-12", setSize = 280, eur = 0.1),
)
private val DARK_RITUALS = listOf(
    card("Dark Ritual", "5ed", "153", "1997-03-24", eur = 2.5),
    card("Dark Ritual", "4bb", "129", "1995-04-01", eur = 12.0, lang = "de"),
)

private val PRINTINGS = mapOf(
    "Swamp" to SWAMPS, "Dark Ritual" to DARK_RITUALS,
    "Lightning Bolt" to BOLTS, "Shock" to SHOCK, "Wasteland" to WASTELAND, "Waste Land" to WASTE_LAND,
)
private val SETS = setOf("phuk", "psal", "5ed", "4bb", "sld", "clu", "2x2", "m11", "m10", "lea", "leb", "m20", "one", "ltc", "tmp", "cmb2", "inr")

private val MATCHER = NameMatcher(listOf(
    "lightningbolt" to "Lightning Bolt", "shock" to "Shock", "firebolt" to "Firebolt",
    "fling" to "Fling", "deliver" to "Stand // Deliver", "delverofsecrets" to "Delver of Secrets",
    "wasteland" to "Wasteland", "wasteland" to "Waste Land", "vodalianmystic" to "Vodalian Mystic",
    "swamp" to "Swamp", "pantano" to "Swamp", "ritualsiniestro" to "Dark Ritual",
))

private fun line(text: String, top: Int, left: Int = 10) = OcrLine(text, left, top, left + 200, top + 20)

class NameMatcherTest {
    @Test fun exactMatch() = assertEquals(listOf("Lightning Bolt"), MATCHER.match("Lightning Bolt")!!.names)

    @Test fun toleratesOcrErrors() {
        val m = MATCHER.match("Lightnlng Bo1t")!!
        assertEquals(listOf("Lightning Bolt"), m.names)
        assertEquals(2, m.distance)
    }

    @Test fun ignoresManaSymbolsReadAsLetters() =
        assertEquals(listOf("Lightning Bolt"), MATCHER.matchWords(listOf("Lightning", "Bolt", "R"))!!.names)

    @Test fun prefersFullLineOverShortPrefix() =
        assertEquals(listOf("Delver of Secrets"), MATCHER.matchWords("Delver of Sccrcts".split(" "))!!.names)

    @Test fun keepsAllNamesThatNormalizeTheSame() =
        assertEquals(setOf("Wasteland", "Waste Land"), MATCHER.match("Wasteland")!!.names.toSet())

    @Test fun rejectsUnrelatedText() = assertNull(MATCHER.match("Creature — Goblin"))

    @Test fun shortNamesNeedExactMatch() {
        assertEquals(listOf("Shock"), MATCHER.match("SHOCK")!!.names)
        assertNull(MATCHER.match("Shoek"))
    }

    @Test fun longerNearMatchBeatsShortExactPrefix() {
        val m = NameMatcher(listOf("reclamation" to "Reclamation", "reclamationsage" to "Reclamation Sage"))
        assertEquals(listOf("Reclamation Sage"), m.matchWords("Reclamation Saqe".split(" "))!!.names)
    }

    @Test fun shortNameIsNotTakenFromTheStartOfASentence() {
        val m = NameMatcher(listOf("when" to "Who // What // When // Where // Why"))
        assertNull(m.matchWords("When Venerable Monk comes into".split(" ")))
        assertNotNull(m.matchWords(listOf("When")))
    }

    @Test fun relaxedMatchForBadlyDamagedReadings() {
        assertNull(MATCHER.match("Nodalian Viystio"))
        assertEquals(listOf("Vodalian Mystic"), MATCHER.matchRelaxed("Nodalian Viystio")!!.names)
    }
}

class CollectorParsingTest {
    private fun parse(vararg lines: String) =
        FrameRecognizer.parseCollector(lines.mapIndexed { i, t -> line(t, 900 + i * 20) }, SETS)

    @Test fun oldFormatWithTotal() {
        val info = parse("163/280 C", "M20 • EN")
        assertEquals("m20", info.setCode)
        assertEquals("163", info.number)
        assertEquals(listOf(280), info.setSizes)
    }

    @Test fun newFormatZeroPadded() {
        val info = parse("C 0141", "CLU • EN Artist Name")
        assertEquals("clu", info.setCode)
        assertEquals("141", info.number)
    }

    @Test fun rarityGluedToNumberAndLanguageGluedToSet() {
        val info = parse("RO219", "INRENK DESCHAPS")
        assertEquals("inr", info.setCode)
        assertEquals("219", info.number)
    }

    @Test fun powerToughnessIsNotACollectorNumber() {
        val info = parse("3/3")
        assertNull(info.number)
        assertTrue(info.setSizes.isEmpty())
    }

    @Test fun fixesOcrLetterDigitConfusion() {
        val info = parse("C O141", "M2O • EN")
        assertEquals("m20", info.setCode)
        assertEquals("141", info.number)
    }

    @Test fun uppercaseWordInRulesTextIsNotAStrongSet() {
        val info = parse("Draw a card. Then discard ONE card at random.")
        assertNull(info.setCode)
    }

    @Test fun readsCopyrightYear() = assertEquals(2001, parse("TM &© 1993-2001 Wizards of the Coast, Inc. 53/350").year)
}

class FrameRecognizerTest {
    private val recognizer = FrameRecognizer(MATCHER, SETS, printingsOf = { PRINTINGS[it].orEmpty() })

    @Test fun exactPrintingFromCollectorLine() {
        val r = recognizer.recognize(listOf(
            line("Lightning Bolt", 50), line("Instant", 500), line("117/332 U", 900), line("2X2 • EN", 920),
        ))!!
        assertEquals("2x2-117", r.printing.id)
        assertTrue(r.exact)
    }

    @Test fun singlePrintingIsExact() {
        val r = recognizer.recognize(listOf(line("Shock", 50), line("Instant", 500)))!!
        assertTrue(r.exact)
    }

    @Test fun ambiguousPicksCheapestRegularPrintingAndNeedsReview() {
        val r = recognizer.recognize(listOf(line("Lightning Bolt", 50), line("Instant", 500)))!!
        assertEquals("clu-141", r.printing.id) // no la de Secret Lair, aunque sea más reciente
        assertFalse(r.exact)
    }

    @Test fun copyrightYearNarrowsOldCards() {
        val r = recognizer.recognize(listOf(line("Lightning Bolt", 50), line("Instant", 500), line("© 1993 Wizards", 900)))!!
        assertEquals("leb-162", r.printing.id)
        assertFalse(r.exact)
    }

    @Test fun numberPlusSetSizeIdentifiesPrintingWithoutSetCode() {
        val r = recognizer.recognize(listOf(line("Lightning Bolt", 50), line("Instant", 500), line("Illus. Someone 149/249", 900)))!!
        assertEquals("m11-149", r.printing.id)
        assertTrue(r.exact)
    }

    @Test fun collidingNamesResolvedByCollectorLine() {
        val r = recognizer.recognize(listOf(line("Wasteland", 50), line("Land", 500), line("MO376", 900), line("LTC • EN", 920)))!!
        assertEquals("ltc-376", r.printing.id)
        assertTrue(r.exact)
    }

    @Test fun keywordBelowTypeLineIsNotTakenAsName() =
        assertNull(recognizer.recognize(listOf(line("Creature", 50), line("Fling", 100))))

    @Test fun letterVariantsWithSamePrintedNumberNeedReview() {
        val plains = listOf(card("Plains", "bfz", "254", "2015-10-02"), card("Plains", "bfz", "254a", "2015-10-02"))
        val r = FrameRecognizer.resolve(plains, CollectorInfo(sets = listOf("bfz"), numbers = listOf("254")))
        assertEquals("bfz-254", r.first.id)
        assertFalse(r.second)
    }

    @Test fun copyrightYearContradictingNumberAndSizeIsNotCertain() {
        // "90/383" casa con Ice Age (1995), pero el copyright dice 2006: es la reimpresión.
        val portents = listOf(
            card("Portent", "cst", "90", "2006-07-21", setSize = 62),
            card("Portent", "ice", "90", "1995-06-03", setSize = 383),
        )
        val r = FrameRecognizer.resolve(portents, CollectorInfo(numbers = listOf("90"), setSizes = listOf(383), year = 2006))
        assertEquals("cst-90", r.first.id)
        assertFalse(r.second)
    }

    @Test fun setCodeWithOneWrongLetterAmongTheCardsEditions() {
        val shatters = listOf(
            card("Shatter", "ktk", "120", "2014-09-26", setSize = 269),
            card("Shatter", "dkm", "20", "2006-12-01", setSize = 26),
        )
        val info = FrameRecognizer.parseCollector(listOf(line("20/26s", 900), line("KTN EN", 920)), SETS)
        val r = FrameRecognizer.resolve(shatters, info)
        assertEquals("ktk-120", r.first.id)
    }

    @Test fun ambiguousSetCorrectionIsIgnored() {
        val putrefies = listOf(
            card("Putrefy", "eoc", "124", "2025-08-01"),
            card("Putrefy", "tdc", "300", "2025-04-11"),
        )
        val info = FrameRecognizer.parseCollector(listOf(line("TOCEN GNE CEARLY", 920)), SETS)
        assertFalse(FrameRecognizer.resolve(putrefies, info).second)
    }

    @Test fun setWithoutNumberResolvesWhenUniqueInSet() {
        val r = FrameRecognizer.resolve(BOLTS, CollectorInfo(sets = listOf("m11")))
        assertEquals("m11-149", r.first.id)
        assertTrue(r.second)
    }

    @Test fun spanishNameTranslatesToEnglishCard() {
        val r = recognizer.recognize(listOf(line("Ritual siniestro", 50), line("Interrupción", 500)))!!
        assertEquals("Dark Ritual", r.name)
    }

    @Test fun languageFromSpanishName() =
        assertEquals("es", recognizer.recognize(listOf(line("Ritual siniestro", 50), line("Interrupción", 500)))!!.lang)

    @Test fun languageFromPrintedCodeWinsOverName() {
        // Nombre igual en ambos idiomas ("Shock"), pero la línea de abajo dice ES.
        val r = recognizer.recognize(listOf(line("Shock", 50), line("Instantáneo", 500), line("163/280 C", 900), line("M20 • ES", 920)))!!
        assertEquals("es", r.lang)
        assertEquals("m20-163", r.printing.id)
    }

    @Test fun englishCardIsEnglish() {
        val r = recognizer.recognize(listOf(line("Shock", 50), line("Instant", 500), line("163/280 C", 900), line("M20 • EN", 920)))!!
        assertEquals("en", r.lang)
    }

    @Test fun recentCardsPrintSpForSpanish() =
        assertEquals("es", FrameRecognizer.parseCollector(listOf(line("R 0163", 900), line("M20 • SP >RUWEN LIU", 920)), SETS).language)

    @Test fun gluedSpanishCodeIsRead() =
        assertEquals("es", FrameRecognizer.parseCollector(listOf(line("R 0163", 900), line("M20ES ARTIST", 920)), SETS).language)

    @Test fun spanishOnlyEditionPreferredForSpanishCards() {
        // Colección Salvat: la carta dice "58/60", Scryfall la numera "A58".
        val r = recognizer.recognize(listOf(
            line("Pantano", 50), line("Tierra básica Pantano", 500), line("TM & 1993-2005 Wizards 58/60", 900),
        ))!!
        assertEquals("psal-A58", r.printing.id)
        assertTrue(r.exact)
    }

    @Test fun englishCardDoesNotPreferSpanishOnlyEditions() {
        val r = recognizer.recognize(listOf(
            line("Swamp", 50), line("Basic Land - Swamp", 500), line("TM & 1993-2005 Wizards 58/60", 900),
        ))!!
        assertEquals("phuk-58", r.printing.id)
    }

    @Test fun noCardText() = assertNull(recognizer.recognize(listOf(line("hello world", 50))))

    @Test fun cardWithoutReadableNameIdentifiedByCollectorLine() {
        // Tierra de arte completo: no hay nombre legible, pero sí "R 1946" y "SLD EN".
        val island = card("Island", "sld", "1946", "2025-01-01", setType = "box")
        val r = FrameRecognizer(
            MATCHER, SETS,
            printingsOf = { name -> PRINTINGS[name].orEmpty() },
            bySetAndNumber = { set, num -> island.takeIf { set == "sld" && num == "1946" } },
        ).recognize(listOf(line("AND", 50), line("R 1946", 900), line("SLD EN >ASHLEY DREYRUS", 920)))!!
        assertEquals("sld-1946", r.printing.id)
        assertTrue(r.exact)
    }
}

class ScanSessionTest {
    private fun frame(name: String, exact: Boolean) =
        FrameResult(name, PRINTINGS.getValue(name)[0], exact, CollectorInfo())

    @Test fun needsTwoFramesThenLocks() {
        val s = ScanSession()
        assertNull(s.onFrame(frame("Shock", true), 0))
        assertNotNull(s.onFrame(frame("Shock", true), 100))
        // Sigue en cámara: no se vuelve a contar.
        assertNull(s.onFrame(frame("Shock", true), 200))
        assertNull(s.onFrame(frame("Shock", true), 300))
    }

    @Test fun sameCardAgainAfterRemoving() {
        val s = ScanSession()
        s.onFrame(frame("Shock", true), 0)
        assertNotNull(s.onFrame(frame("Shock", true), 100))
        s.onFrame(null, 1200) // la carta ha salido
        assertNull(s.onFrame(frame("Shock", true), 1300))
        assertNotNull(s.onFrame(frame("Shock", true), 1400))
    }

    @Test fun differentCardIsAddedImmediately() {
        val s = ScanSession()
        s.onFrame(frame("Shock", true), 0)
        s.onFrame(frame("Shock", true), 100)
        assertNull(s.onFrame(frame("Lightning Bolt", true), 200))
        assertEquals("Lightning Bolt", s.onFrame(frame("Lightning Bolt", true), 300)!!.name)
    }

    @Test fun ambiguousWaitsForExactReading() {
        val s = ScanSession()
        assertNull(s.onFrame(frame("Lightning Bolt", false), 0))
        assertNull(s.onFrame(frame("Lightning Bolt", false), 100))
        val r = s.onFrame(frame("Lightning Bolt", true), 200)
        assertTrue(r!!.exact)
    }

    @Test fun samePrintingStaysLocked() {
        val s = ScanSession()
        val bolt = FrameResult("Lightning Bolt", BOLTS[2], true, CollectorInfo())
        s.onFrame(bolt, 0)
        assertNotNull(s.onFrame(bolt, 100))
        for (t in 200..3000 step 100) assertNull(s.onFrame(bolt, t.toLong()))
    }

    @Test fun otherExactPrintingOfSameNameIsANewCard() {
        // Pantano de una edición y luego pantano de otra, sin retirar la mano.
        val s = ScanSession()
        val a = FrameResult("Lightning Bolt", BOLTS[2], true, CollectorInfo())
        val b = FrameResult("Lightning Bolt", BOLTS[3], true, CollectorInfo())
        s.onFrame(a, 0)
        assertNotNull(s.onFrame(a, 100))
        assertNull(s.onFrame(b, 200))
        assertEquals(BOLTS[3].id, s.onFrame(b, 300)!!.printing.id)
    }

    @Test fun shortGapBetweenIdenticalCopies() {
        val s = ScanSession()
        s.onFrame(frame("Shock", true), 0)
        assertNotNull(s.onFrame(frame("Shock", true), 100))
        s.onFrame(null, 400)
        s.onFrame(null, 750) // la mano tapa el marco ~0,6 s mientras se cambia la carta
        assertNull(s.onFrame(frame("Shock", true), 850))
        assertNotNull(s.onFrame(frame("Shock", true), 950))
    }

    @Test fun pendingAmbiguousCardIsAddedWhenNextCardArrives() {
        val s = ScanSession()
        s.onFrame(frame("Lightning Bolt", false), 0)
        assertNull(s.onFrame(frame("Lightning Bolt", false), 200)) // esperando la edición
        val flushed = s.onFrame(frame("Shock", true), 400)          // llega otra carta
        assertEquals("Lightning Bolt", flushed!!.name)
        assertFalse(flushed.exact)
        assertEquals("Shock", s.onFrame(frame("Shock", true), 600)!!.name)
    }

    @Test fun pendingAmbiguousCardIsAddedWhenRemoved() {
        val s = ScanSession()
        s.onFrame(frame("Lightning Bolt", false), 0)
        s.onFrame(frame("Lightning Bolt", false), 200)
        assertNull(s.onFrame(null, 400))
        assertEquals("Lightning Bolt", s.onFrame(null, 600)!!.name)
    }

    @Test fun singleMisreadIsNeverAdded() {
        val s = ScanSession()
        s.onFrame(frame("Shock", true), 0)                 // una sola lectura suelta
        assertNull(s.onFrame(frame("Lightning Bolt", true), 200))
        assertNull(s.onFrame(null, 800))
    }

    @Test fun ambiguousIsAddedAfterTimeout() {
        val s = ScanSession()
        s.onFrame(frame("Lightning Bolt", false), 0)
        assertNull(s.onFrame(frame("Lightning Bolt", false), 500))
        val r = s.onFrame(frame("Lightning Bolt", false), 1000)
        assertFalse(r!!.exact)
    }
}

class GuideRegionTest {
    // Móvil vertical: vista 1080×2200, imagen de análisis 1080×1920 (ya girada).
    private val region = com.hernan.mtgscanner.scan.GuideRegion.inImage(1080, 2200, 1080, 1920)

    @Test fun cardInsideGuideIsKept() {
        val name = OcrLine("Shock", 300, 600, 700, 640)
        assertEquals(1, com.hernan.mtgscanner.scan.GuideRegion.filter(listOf(name), region).size)
    }

    @Test fun cardAboveGuideIsIgnored() {
        // Otra carta asomando por arriba del marco: su nombre no debe contar.
        val other = OcrLine("Lightning Bolt", 300, 60, 700, 100)
        assertTrue(com.hernan.mtgscanner.scan.GuideRegion.filter(listOf(other), region).isEmpty())
    }

    @Test fun guideIsCenteredWithCardProportions() {
        val cx = (region.left + region.right) / 2
        val cy = (region.top + region.bottom) / 2
        assertEquals(540f, cx, 1f)
        assertEquals(960f, cy, 1f)
        assertEquals(88f / 63f, (region.bottom - region.top) / (region.right - region.left), 0.01f)
    }
}

class ArtSignatureTest {
    private fun pattern(x: Int, y: Int): Int {
        val r = (x * 7 + y * 3) % 256
        val g = (x * x + y) % 256
        val b = ((x / 10) * (y / 10) * 13) % 256
        return (r shl 16) or (g shl 8) or b
    }

    /** La miniatura de la foto debe parecerse a la que calcula Python para la misma imagen. */
    @Test fun matchesPythonThumbnail() {
        // Python recorta un 4 % por lado antes de reducir (THUMB_INSET).
        val photo = com.hernan.mtgscanner.scan.ArtSignature.compute(8f, 6f, 192f, 144f, ::pattern)
        val hex = "870c81381772c62563684b74775576a16977477978a89369a81993484976b7837988bc7d381783c25486688b7a68c57dc72485677b7a68c586a6488747797aa2c378874c8637977ca8237788ac77382786c7848b672b8563758ba76883486a8a683589a7a882487989a74386876c88345887c8a386784c7938478ac77376699c86578678b85789559a8b98847b983b88495a8b974487985b88388887c8538a758c8a586688b88686785c8d577689b97787588a8498648496ab89389876c9b385996b83376884c95386783c87483686b75686587a83899483b87672579a6688847a979b81377885c78389788c8d467683c9837476cc75476667b7a579584a8888948a985a7a46697e89947697aa7936887bb8837b886c8839a787c9b578674b7c"
        val ref = com.hernan.mtgscanner.scan.ArtSignature.fromPacked(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        assertTrue("similitud ${photo.similarity(ref)}", photo.similarity(ref) > 0.9)
    }

    /** Imagen suave (como una ilustración, no ruido): degradados y manchas. */
    private fun smooth(x: Int, y: Int): Int {
        val r = (128 + 100 * kotlin.math.sin(x / 23.0) * kotlin.math.cos(y / 31.0)).toInt()
        val g = (128 + 90 * kotlin.math.sin((x + y) / 40.0)).toInt()
        val b = (128 + 80 * kotlin.math.cos(x / 17.0 - y / 29.0)).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    @Test fun toleratesBrightnessAndSmallShifts() {
        val a = com.hernan.mtgscanner.scan.ArtSignature.compute(0f, 0f, 200f, 150f, ::smooth)
        val darker = com.hernan.mtgscanner.scan.ArtSignature.compute(6f, 4f, 206f, 154f) { x, y ->
            val c = smooth(x, y)
            (((c shr 16) and 0xFF) * 7 / 10 shl 16) or (((c shr 8) and 0xFF) * 7 / 10 shl 8) or ((c and 0xFF) * 7 / 10)
        }
        val other = com.hernan.mtgscanner.scan.ArtSignature.compute(0f, 0f, 200f, 150f) { x, y -> smooth(y * 2 + 50, x / 2) }
        assertTrue("oscurecida ${a.similarity(darker)}", a.similarity(darker) > 0.9)
        assertTrue("otra ${a.similarity(other)}", a.similarity(darker) - a.similarity(other) > 0.15)
    }
}

class RotationTest {
    /** Gira una imagen del sensor como lo hace la cámara y comprueba que se deshace bien. */
    @Test fun uprightToSensorUndoesRotation() {
        val w = 4; val h = 3
        val sensor = Array(h) { y -> IntArray(w) { x -> y * 10 + x } }
        for (rotation in listOf(0, 90, 180, 270)) {
            // Imagen derecha: girar el sensor en sentido horario.
            val (uw, uh) = if (rotation % 180 == 0) w to h else h to w
            val upright = Array(uh) { yy -> IntArray(uw) { xx ->
                when (rotation) {
                    90 -> sensor[h - 1 - xx][yy]
                    180 -> sensor[h - 1 - yy][w - 1 - xx]
                    270 -> sensor[xx][w - 1 - yy]
                    else -> sensor[yy][xx]
                }
            } }
            for (yy in 0 until uh) for (xx in 0 until uw) {
                val (sx, sy) = com.hernan.mtgscanner.scan.uprightToSensor(xx, yy, rotation, w, h)
                assertEquals("rotación $rotation en ($xx,$yy)", upright[yy][xx], sensor[sy][sx])
            }
        }
    }
}

class ArtRegionTest {
    @Test fun artIsBetweenNameAndTypeLineAcrossTheRulesText() {
        val lines = listOf(
            OcrLine("Lightning Bolt", 120, 100, 520, 130),
            OcrLine("Instant", 120, 620, 330, 650),
            OcrLine("Lightning Bolt deals 3 damage to any target.", 110, 700, 960, 730),
            OcrLine("\"The sparkmage shrieked...\"", 110, 740, 900, 770),
        )
        val r = FrameRecognizer.artRegion(lines, 0)!!
        assertTrue(r.top > 130 && r.bottom < 620)
        assertTrue(r.left >= 110 && r.right <= 960)
        assertTrue((r.right - r.left) > (r.bottom - r.top)) // la ilustración es apaisada
    }

    @Test fun noTypeLineNoRegion() =
        assertNull(FrameRecognizer.artRegion(listOf(OcrLine("Lightning Bolt", 120, 100, 520, 130)), 0))
}
