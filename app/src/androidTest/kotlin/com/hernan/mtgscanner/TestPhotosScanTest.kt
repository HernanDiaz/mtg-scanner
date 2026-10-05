package com.hernan.mtgscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.TestPhotos
import com.hernan.mtgscanner.scan.ArtSampler
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.BitmapFrame
import com.hernan.mtgscanner.scan.OcrLine
import com.hernan.mtgscanner.ui.ScannerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Fotos de prueba de principio a fin, sin cámara: fotos de cartas de testset/ingles
 * pasan por el escáner como fotogramas (OCR, reconocimiento, ScanSession, añadir a la
 * colección, guardar la foto); luego se corrige una carta y se borra otra, se exporta
 * el zip y se comprueba que las respuestas reflejan las correcciones. El zip se deja
 * descomprimido en testset/movil_e2e para evaluarlo con tools/run_eval.sh.
 *
 * Usa la colección de la app: al terminar borra lo que añadió.
 */
@RunWith(AndroidJUnit4::class)
class TestPhotosScanTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext.applicationContext as App

    @Test fun scanCorrectExportAndLabel() {
        val files = instr.targetContext.getExternalFilesDir(null)!!
        val source = File(files, "testset/ingles")
        val rows = source.resolve("labels.csv").readLines().drop(1)
            .filter { '"' !in it }.map { it.split(',') }
            .filter { it[6] == "normal" }.distinctBy { it[2] }.take(CARDS)
        assertEquals(CARDS, rows.size)

        val wasOn = app.settings.testPhotos.value
        app.settings.setTestPhotos(true)
        app.testPhotos.clear()
        val catalog = appCatalog()
        val recognizer = FrameRecognizer(catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber,
            catalog::art, catalog.symbols, catalog::nameLanguages, catalog::setLanguages)
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        lateinit var vm: ScannerViewModel
        instr.runOnMainSync { vm = ScannerViewModel(app) }

        try {
            // Cada carta: ~1,2 s delante de la cámara y luego fuera del marco.
            for (row in rows) {
                val bitmap = BitmapFactory.decodeFile(source.resolve(row[0]).path)!!
                val text = Tasks.await(ocr.process(InputImage.fromBitmap(bitmap, 0)))
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { OcrLine(l.text, it.left, it.top, it.right, it.bottom) }
                }
                val art = ArtSampler { x, y -> bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)) and 0xFFFFFF }
                val result = recognizer.recognize(lines, art)
                repeat(6) { vm.onFrame(result, null) { BitmapFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false)) }; SystemClock.sleep(200) }
                repeat(5) { vm.onFrame(null, null) { BitmapFrame(bitmap) }; SystemClock.sleep(100) }
            }
            // Las fotos se guardan en segundo plano.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (vm.ui.value.testPhotoCount < vm.ui.value.summary.cards && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
            }
            SystemClock.sleep(1000)

            val photos = app.testPhotos.photos()
            println("E2E: ${rows.size} cartas, ${photos.size} fotos: " + photos.joinToString { "${it.gotName} [${it.gotSet} ${it.gotNumber}]" })
            assertTrue("se reconocieron pocas: ${photos.size}", photos.size >= CARDS - 2)
            photos.forEach { assertNotNull("foto sin entrada", it.entryId) }

            // Como haría el usuario: corregir la edición de una y borrar otra.
            val store = app.collection
            val groups = store.groups(Currency.EUR)
            val fixed = photos[0]
            val fixedGroup = groups.first { it.lastEntryId == fixed.entryId }
            val other = catalog.printingsOf(fixedGroup.name).first { it.id != fixedGroup.cardId }
            store.reassign(fixedGroup, other)
            val removed = photos[1]
            store.delete(removed.entryId!!)

            val zip = File(instr.targetContext.cacheDir, "e2e.zip")
            app.testPhotos.export(zip.path, store::entriesNow)
            val out = File(files, "testset/movil_e2e").apply { deleteRecursively(); mkdirs() }
            ZipFile(zip).use { z ->
                z.entries().asSequence().forEach { e -> z.getInputStream(e).use { i -> File(out, e.name).outputStream().use { i.copyTo(it) } } }
            }
            val labels = File(out, "labels.csv").readLines()
            val header = labels[0].split(',')
            val byFile = labels.drop(1).associate { l -> l.split(',').let { it[0] to header.zip(it).toMap() } }
            assertEquals(photos.size, byFile.size)
            assertEquals(other.id, byFile.getValue(fixed.file)["card_id"])
            assertEquals("movil", byFile.getValue(fixed.file)["group"])
            assertEquals("movil_borrada", byFile.getValue(removed.file)["group"])
            // Las demás: la carta añadida, y la foto es el fotograma entero.
            photos.drop(2).forEach { p ->
                val r = byFile.getValue(p.file)
                assertTrue(r["card_id"]!!.isNotEmpty())
                val img = BitmapFactory.decodeFile(File(out, p.file).path)
                assertTrue(img.width > 500 && img.height > 700)
            }
            zip.delete()
        } finally {
            // Dejar la colección como estaba.
            app.testPhotos.photos().mapNotNull { it.entryId }.forEach { app.collection.delete(it) }
            app.testPhotos.clear()
            app.settings.setTestPhotos(wasOn)
            ocr.close()
        }
    }

    private companion object {
        const val CARDS = 8
    }
}
