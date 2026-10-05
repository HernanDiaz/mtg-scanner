package com.hernan.mtgscanner.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hernan.mtgscanner.App
import okio.Sink
import okio.sink
import java.util.concurrent.TimeUnit

/**
 * El fichero de la copia automática en Android: creado con ACTION_CREATE_DOCUMENT (Drive,
 * Dropbox, el móvil...), con permiso persistente para seguir escribiéndolo. Drive no deja
 * elegir carpetas desde otras apps, pero sí crear un fichero y seguir escribiéndolo;
 * además guarda sus versiones anteriores.
 */
class AndroidBackupTarget(private val context: Context) : BackupTarget {
    private val resolver get() = context.contentResolver

    override fun keep(uri: String): String {
        resolver.takePersistableUriPermission(Uri.parse(uri), RW)
        return uri
    }

    override fun release(kept: String) = resolver.releasePersistableUriPermission(Uri.parse(kept), RW)

    override fun displayName(kept: String): String? = runCatching {
        resolver.query(Uri.parse(kept), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** "wt" vacía el fichero antes; algunos proveedores solo admiten "w". */
    override fun openSink(kept: String): Sink {
        val uri = Uri.parse(kept)
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("no se puede escribir")
        return out.sink()
    }

    private companion object {
        const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** La copia diaria con WorkManager (con batería suficiente). */
class WorkManagerBackupScheduler(private val context: Context) : BackupScheduler {
    override fun schedule(replace: Boolean) {
        // Hasta la versión 0.1 la tarea era "auto_backup" con la clase AutoBackup.Worker, que ya no existe.
        WorkManager.getInstance(context).cancelUniqueWork(OLD_WORK)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun runSoon() {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<BackupWorker>().build())
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private companion object {
        const val WORK = "auto_backup_v2"
        const val OLD_WORK = "auto_backup"
    }
}

/** La copia diaria, en segundo plano. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when ((applicationContext as App).autoBackup.run()) {
            is AutoBackup.Outcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
}
