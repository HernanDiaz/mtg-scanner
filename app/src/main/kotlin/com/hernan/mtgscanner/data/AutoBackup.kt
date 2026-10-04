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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Copia automática: cada día, si la colección cambió, reescribe un fichero que el
 * usuario eligió una vez (en Google Drive, Dropbox, el móvil...). Drive no deja
 * elegir carpetas desde otras apps, pero sí crear un fichero y seguir escribiéndolo;
 * además guarda sus versiones anteriores.
 *
 * Sus preferencias no van en la copia de Android: el permiso sobre el fichero no
 * sirve en otro móvil (ver res/xml/backup_rules.xml).
 */
class AutoBackup(private val app: App) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    data class State(
        val file: Uri? = null,
        val fileName: String? = null,
        /** Última copia escrita (ms), o 0. */
        val lastSaved: Long = 0,
        val lastCards: Int = 0,
        /** Último intento sin cambios que copiar (ms), o 0. */
        val lastChecked: Long = 0,
        val lastError: String? = null,
        /** Con fotos: un segundo fichero, un zip con el CSV y las fotos ([CardPhotos]). */
        val photosFile: Uri? = null,
        val photosFileName: String? = null,
        val lastPhotosSaved: Long = 0,
        val lastPhotos: Int = 0,
    )

    sealed interface Outcome {
        data class Saved(val cards: Int) : Outcome
        data object Unchanged : Outcome
        data object NoFile : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state

    private fun read() = State(
        file = prefs.getString("file", null)?.let(Uri::parse),
        fileName = prefs.getString("file_name", null),
        lastSaved = prefs.getLong("last_saved", 0),
        lastCards = prefs.getInt("last_cards", 0),
        lastChecked = prefs.getLong("last_checked", 0),
        lastError = prefs.getString("last_error", null),
        photosFile = prefs.getString("photos_file", null)?.let(Uri::parse),
        photosFileName = prefs.getString("photos_file_name", null),
        lastPhotosSaved = prefs.getLong("last_photos_saved", 0),
        lastPhotos = prefs.getInt("last_photos", 0),
    )

    /** Al arrancar la app: que siga programada si está activa. */
    fun ensureScheduled() {
        if (_state.value.file != null) schedule(ExistingPeriodicWorkPolicy.KEEP)
    }

    /** El fichero recién creado por el usuario (ACTION_CREATE_DOCUMENT); lo escribe ya. */
    fun enable(uri: Uri) {
        val resolver = app.contentResolver
        _state.value.file?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, RW) }
        }
        resolver.takePersistableUriPermission(uri, RW)
        val photos = _state.value.photosFile
        prefs.edit().clear()
            .putString("photos_file", photos?.toString())
            .putString("photos_file_name", _state.value.photosFileName)
            .putString("file", uri.toString())
            .putString("file_name", displayName(uri))
            .apply()
        _state.value = read()
        schedule(ExistingPeriodicWorkPolicy.UPDATE)
        WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<Worker>().build())
    }

    /** El zip de fotos recién creado por el usuario (ACTION_CREATE_DOCUMENT); lo escribe ya. */
    fun enablePhotos(uri: Uri) {
        val resolver = app.contentResolver
        _state.value.photosFile?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, RW) }
        }
        resolver.takePersistableUriPermission(uri, RW)
        prefs.edit()
            .putString("photos_file", uri.toString())
            .putString("photos_file_name", displayName(uri))
            .remove("photos_fingerprint").remove("last_photos_saved").remove("last_photos")
            .apply()
        _state.value = read()
        WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<Worker>().build())
    }

    /** Deja de copiar las fotos (el CSV sigue). */
    fun disablePhotos() {
        _state.value.photosFile?.let { runCatching { app.contentResolver.releasePersistableUriPermission(it, RW) } }
        prefs.edit().remove("photos_file").remove("photos_file_name").remove("photos_fingerprint")
            .remove("last_photos_saved").remove("last_photos").apply()
        _state.value = read()
    }

    fun disable() {
        _state.value.file?.let { runCatching { app.contentResolver.releasePersistableUriPermission(it, RW) } }
        _state.value.photosFile?.let { runCatching { app.contentResolver.releasePersistableUriPermission(it, RW) } }
        prefs.edit().clear().apply()
        _state.value = read()
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
    }

    /** Escribe la copia si la colección cambió desde la última ([force]: aunque no cambiara). */
    suspend fun run(force: Boolean = false): Outcome = mutex.withLock {
        val uri = _state.value.file ?: return Outcome.NoFile
        val now = System.currentTimeMillis()
        val outcome = runCatching {
            val content = CollectionBackup.build(app)
            val photosChanged = runPhotos(force, content.fingerprint, now)
            if (!force && content.fingerprint == prefs.getString("fingerprint", null)) {
                prefs.edit().putLong("last_checked", now).remove("last_error").apply()
                return@runCatching if (photosChanged) Outcome.Saved(content.cards) else Outcome.Unchanged
            }
            withContext(Dispatchers.IO) { write(uri, content.csv.toByteArray(Charsets.UTF_8)) }
            prefs.edit()
                .putString("fingerprint", content.fingerprint)
                .putLong("last_saved", now).putInt("last_cards", content.cards)
                .putLong("last_checked", now).remove("last_error")
                .apply()
            Outcome.Saved(content.cards)
        }.getOrElse { e ->
            val msg = e.message ?: e.javaClass.simpleName
            prefs.edit().putString("last_error", msg).apply()
            Outcome.Failed(msg)
        }
        _state.value = read()
        outcome
    }

    /**
     * El zip con las fotos, si está activado y cambiaron las cartas o las fotos. Puede ocupar
     * decenas de MB: solo se reescribe cuando hace falta. Devuelve si lo escribió.
     */
    private suspend fun runPhotos(force: Boolean, cardsFingerprint: String, now: Long): Boolean {
        val uri = _state.value.photosFile ?: return false
        val fingerprint = cardsFingerprint + app.cardPhotos.fingerprint()
        if (!force && fingerprint == prefs.getString("photos_fingerprint", null)) return false
        val content = CollectionBackup.build(app, photos = true)
        withContext(Dispatchers.IO) {
            open(uri).use { out -> app.cardPhotos.zip(out, ZIP_CSV_NAME, content.csv, content.photoNames) }
        }
        prefs.edit().putString("photos_fingerprint", fingerprint)
            .putLong("last_photos_saved", now).putInt("last_photos", content.photoNames.size).apply()
        return true
    }

    /** "wt" vacía el fichero antes; algunos proveedores solo admiten "w". */
    private fun open(uri: Uri) = app.contentResolver.let { resolver ->
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("no se puede escribir")
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        open(uri).use { it.write(bytes) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun schedule(policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK, policy, request)
    }

    /** La copia diaria, en segundo plano. */
    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when ((applicationContext as App).autoBackup.run()) {
                is Outcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
                else -> Result.success()
            }
    }

    companion object {
        const val PREFS = "auto_backup"
        private const val WORK = "auto_backup"
        const val FILE_NAME = "mtg-scanner-auto.csv"
        const val PHOTOS_FILE_NAME = "mtg-scanner-auto-fotos.zip"
        private const val ZIP_CSV_NAME = "coleccion.csv"
        private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
