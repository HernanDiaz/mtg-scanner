package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.scan.toByteArray
import com.hernan.mtgscanner.ui.toNSData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okio.Buffer
import okio.FileSystem
import okio.ForwardingSink
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.Sink
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationMinimalBookmark
import platform.Foundation.URLByResolvingBookmarkData
import platform.Foundation.bookmarkDataWithOptions

/**
 * El fichero de la copia automática en iPhone (creado con el selector de documentos en
 * iCloud Drive, Archivos...). Para volver a escribirlo en otra sesión se guarda un
 * marcador de seguridad (bookmark) en vez de la dirección.
 */
@OptIn(ExperimentalEncodingApi::class)
class IosBackupTarget : BackupTarget {

    override fun keep(uri: String): String {
        val url = NSURL.URLWithString(uri) ?: error("dirección no válida")
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val data = url.bookmarkDataWithOptions(NSURLBookmarkCreationMinimalBookmark, includingResourceValuesForKeys = null,
                relativeToURL = null, error = null) ?: error("no se puede guardar el acceso al fichero")
            return BOOKMARK + Base64.encode(data.toByteArray())
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun release(kept: String) {}

    override fun displayName(kept: String): String? = resolve(kept)?.lastPathComponent

    override fun openSink(kept: String): Sink {
        val url = resolve(kept) ?: error("No se encuentra el fichero de la copia: elígelo otra vez")
        val scoped = url.startAccessingSecurityScopedResource()
        val path = url.path ?: error("dirección no válida")
        val sink = FileSystem.SYSTEM.sink(path.toPath())
        return object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) = super.write(source, byteCount)
            override fun close() {
                try {
                    super.close()
                } finally {
                    if (scoped) url.stopAccessingSecurityScopedResource()
                }
            }
        }
    }

    private fun resolve(kept: String): NSURL? {
        if (!kept.startsWith(BOOKMARK)) return NSURL.URLWithString(kept)
        val data: NSData = Base64.decode(kept.removePrefix(BOOKMARK)).toNSData()
        return NSURL.URLByResolvingBookmarkData(data, options = 0u, relativeToURL = null, bookmarkDataIsStale = null, error = null)
    }

    private companion object {
        const val BOOKMARK = "bookmark:"
    }
}

/**
 * En iPhone no hay tareas diarias fiables en segundo plano: la copia se hace al abrir la
 * app y un minuto después de cada cambio en la colección (si cambió desde la última).
 */
@OptIn(FlowPreview::class)
class IosBackupScheduler(
    private val scope: CoroutineScope,
    private val store: CollectionStore,
    private val backup: () -> AutoBackup,
) : BackupScheduler {
    private var job: Job? = null

    override fun schedule(replace: Boolean) {
        if (job != null && !replace) return
        job?.cancel()
        job = scope.launch {
            backup().run()
            store.version.drop(1).debounce(60_000).collect { backup().run() }
        }
    }

    override fun runSoon() {
        scope.launch { backup().run() }
    }

    override fun cancel() {
        job?.cancel()
        job = null
    }
}
