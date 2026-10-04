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
import com.hernan.mtgscanner.data.CollectionBackup
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.scan.ArtSampler
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.OcrLine
import com.hernan.mtgscanner.ui.ScannerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Fotos de las cartas de principio a fin, sin cámara: unas cartas de testset/ingles pasan
 * por el escáner con la opción activada; cada copia añadida tiene su foto, el zip de la
 * copia lleva el CSV con la columna de fotos y las fotos, y al quitar las cartas sus
 * fotos se borran.
 *
 * Usa la colección de la app: al terminar borra lo que añadió.
 */
@RunWith(AndroidJUnit4::class)
class CardPhotosScanTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext.applicationContext as App

    @Test fun scanSavesPhotosAndZipsThem() {
        val files = instr.targetContext.getExternalFilesDir(null)!!
        val source = File(files, "testset/ingles")
        val rows = source.resolve("labels.csv").readLines().drop(1)
            .filter { '"' !in it }.map { it.split(',') }
            .filter { it[6] == "normal" }.distinctBy { it[2] }.take(CARDS)

        val wasOn = app.settings.cardPhotos.value
        val wasTesting = app.settings.testPhotos.value
        app.settings.setCardPhotos(true)
        app.settings.setTestPhotos(false)
        val before = app.collection.allEntryIds()
        val catalog = appCatalog()
        val recognizer = FrameRecognizer(catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber,
            catalog::art, catalog.symbols, catalog::nameLanguages, catalog::setLanguages)
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        lateinit var vm: ScannerViewModel
        instr.runOnMainSync { vm = ScannerViewModel(app) }

        try {
            for (row in rows) {
                val bitmap = BitmapFactory.decodeFile(source.resolve(row[0]).path)!!
                val text = Tasks.await(ocr.process(InputImage.fromBitmap(bitmap, 0)))
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { OcrLine(l.text, it.left, it.top, it.right, it.bottom) }
                }
                val art = ArtSampler { x, y -> bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)) and 0xFFFFFF }
                val result = recognizer.recognize(lines, art)
                repeat(6) { vm.onFrame(result, null) { bitmap.copy(Bitmap.Config.ARGB_8888, false) }; SystemClock.sleep(200) }
                repeat(5) { vm.onFrame(null, null) { bitmap }; SystemClock.sleep(100) }
            }
            SystemClock.sleep(2000) // se guardan en segundo plano

            val added = app.collection.allEntryIds() - before
            println("FOTOS: ${rows.size} cartas, ${added.size} añadidas, ${app.cardPhotos.of(added).size} fotos")
            assertTrue("se añadieron pocas: ${added.size}", added.size >= CARDS - 1)
            assertEquals(added.size, app.cardPhotos.of(added).size)
            app.cardPhotos.of(added).forEach { f ->
                val img = BitmapFactory.decodeFile(f.path)
                assertTrue("foto demasiado pequeña o grande: ${img.width}x${img.height}", maxOf(img.width, img.height) in 400..1280)
            }

            // La ficha ve las fotos de su fila.
            val group = app.collection.groups(Currency.EUR).first { it.lastEntryId in added }
            assertTrue(app.cardPhotos.of(app.collection.entryIds(group)).isNotEmpty())

            // El zip de la copia: el CSV con la columna de fotos y las fotos que nombra.
            val content = runBlocking { CollectionBackup.build(app, photos = true) }
            assertTrue(content.photoNames.keys.containsAll(added))
            val zip = File(instr.targetContext.cacheDir, "fotos-e2e.zip")
            zip.outputStream().use { app.cardPhotos.zip(it, "coleccion.csv", content.csv, content.photoNames) }
            ZipFile(zip).use { z ->
                val names = z.entries().asSequence().map { it.name }.toSet()
                val csv = z.getInputStream(z.getEntry("coleccion.csv")).reader().readText()
                assertTrue(csv.lineSequence().first().trimEnd().endsWith("Fotos") || csv.lineSequence().first().trimEnd().endsWith("Photos"))
                content.photoNames.values.forEach { n ->
                    assertTrue("falta $n", "fotos/$n" in names)
                    assertTrue("el CSV no nombra $n", csv.contains("fotos/$n"))
                }
            }
            zip.delete()
        } finally {
            // Dejar la colección como estaba; las fotos sin carta se borran.
            (app.collection.allEntryIds() - before).forEach { app.collection.delete(it) }
            app.cardPhotos.removeOrphans(app.collection.allEntryIds())
            app.settings.setCardPhotos(wasOn)
            app.settings.setTestPhotos(wasTesting)
            ocr.close()
        }
        assertEquals(0, app.cardPhotos.of(app.collection.allEntryIds() - before).size)
    }

    private companion object {
        const val CARDS = 5
    }
}
