package com.hernan.mtgscanner.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.hernan.mtgscanner.ui.PlatformFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ficheros elegidos por el usuario (Storage Access Framework) y el menú de compartir.
 * Los ficheros para compartir se escriben en la caché (cache/exports, ver file_paths.xml).
 */
class AndroidFiles(private val context: Context) : PlatformFiles {

    override suspend fun read(uri: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uri))!!.use { input ->
            val data = input.readBytes()
            require(data.size <= maxBytes) { "> ${maxBytes / 1_048_576} MB" }
            data
        }
    }

    override fun displayName(uri: String): String? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override suspend fun write(uri: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(bytes) }
    }

    override fun shareText(text: String, subject: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        start(Intent.createChooser(send, title))
    }

    override fun sharePath(path: String, mime: String, subject: String?, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        start(Intent.createChooser(send, title))
    }

    override fun exportPath(name: String): String = exportFile(name).path

    /** Un fichero nuevo en cache/exports (borra los de otras veces con la misma extensión). */
    fun exportFile(name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val ext = name.substringAfterLast('.', "")
        dir.listFiles { f -> f.name.endsWith(".$ext") }?.forEach { it.delete() }
        return File(dir, name)
    }

    /** Desde fuera de una actividad (el contexto de la app) hace falta una tarea nueva. */
    private fun start(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
