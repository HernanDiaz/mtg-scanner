package com.hernan.mtgscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.AutoBackup
import com.hernan.mtgscanner.data.BackupScheduler
import com.hernan.mtgscanner.data.BackupTarget
import com.hernan.mtgscanner.data.CollectionStore
import kotlinx.coroutines.runBlocking
import okio.Sink
import okio.sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * La copia automática (común con iPhone) con un destino de prueba (ficheros en la caché):
 * escribe el CSV, no repite una copia igual, vuelve a copiar al cambiar la colección y,
 * con fotos, escribe el zip. Usa la colección de la app (deja sus cartas como estaban) y las
 * preferencias de la copia automática: al terminar queda desactivada (es para el emulador).
 */
@RunWith(AndroidJUnit4::class)
class AutoBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as App

    private class FileTarget(private val dir: File) : BackupTarget {
        override fun keep(uri: String) = uri
        override fun release(kept: String) {}
        override fun displayName(kept: String) = File(dir, kept).name
        override fun openSink(kept: String): Sink = File(dir, kept).sink()
    }

    private object NoScheduler : BackupScheduler {
        override fun schedule(replace: Boolean) {}
        override fun runSoon() {}
        override fun cancel() {}
    }

    @Test fun writesOnlyWhenTheCollectionChanges() = runBlocking {
        val dir = File(context.cacheDir, "auto_backup_test").apply { deleteRecursively(); mkdirs() }
        val backup = AutoBackup(app, FileTarget(dir), NoScheduler)
        val catalog = app.catalog.await()
        val before = app.collection.allEntryIds()
        try {
            backup.enable("copia.csv")
            assertEquals("copia.csv", backup.state.value.fileName)
            val first = backup.run()
            assertTrue("primera copia: $first", first is AutoBackup.Outcome.Saved)
            val csv = File(dir, "copia.csv")
            assertTrue(csv.length() > 0)
            assertEquals(AutoBackup.Outcome.Unchanged, backup.run())

            // Una carta más: se vuelve a copiar.
            val id = app.collection.add(catalog.printingsOf("Lightning Bolt").first(), false, "NM", needsReview = false)
            assertTrue(backup.run() is AutoBackup.Outcome.Saved)
            assertTrue(csv.readText().contains("Lightning Bolt"))

            // Con fotos: el zip con el CSV.
            backup.enablePhotos("fotos.zip")
            assertTrue(backup.run(force = true) is AutoBackup.Outcome.Saved)
            ZipFile(File(dir, "fotos.zip")).use { z -> assertTrue(z.getEntry("coleccion.csv") != null) }
            app.collection.delete(id)
        } finally {
            backup.disable()
            (app.collection.allEntryIds() - before).forEach { app.collection.delete(it) }
            dir.deleteRecursively()
        }
    }
}
