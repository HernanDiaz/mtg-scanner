package com.hernan.mtgscanner

import com.hernan.mtgscanner.scan.BitmapFrame
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.TestPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Guardar fotos de prueba y exportarlas en un zip con labels.csv. */
@RunWith(AndroidJUnit4::class)
class TestPhotosExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun saveAndExport() {
        val dir = File(context.cacheDir, "test_photos_test").apply { deleteRecursively() }
        val photos = TestPhotos(dir.path)
        val bmp = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888)
        photos.save(BitmapFrame(bmp), TestPhotos.Photo("a.jpg", 7, "Lightning Bolt", "m11", "149", true, "en", 1080, 2200, 1))
        photos.save(BitmapFrame(bmp), TestPhotos.Photo("b.jpg", null, null, null, null, false, null, 1080, 2200, 2))
        assertEquals(2, photos.count())

        val zip = File(context.cacheDir, "test_photos_test.zip")
        val n = photos.export(zip.path) { ids ->
            ids.associateWith { CollectionStore.EntryNow("id-a25", "Lightning Bolt", "a25", "141", "es", false) }
        }
        assertEquals(2, n)
        ZipFile(zip).use { z ->
            val labels = z.getInputStream(z.getEntry("labels.csv")).reader().readText().trim().lines()
            assertEquals(3, labels.size)
            assertTrue(labels[1], labels[1].startsWith("a.jpg,id-a25,Lightning Bolt,a25,141,es,movil,"))
            assertTrue(labels[2], labels[2].startsWith("b.jpg,,,,,,movil_manual,"))
            val img = BitmapFactory.decodeStream(z.getInputStream(z.getEntry("a.jpg")))
            assertNotNull(img)
            assertEquals(960, img.width)
        }
        photos.clear()
        assertEquals(0, photos.count())
        zip.delete()
    }
}
