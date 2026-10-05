package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.ui.AppServices
import com.hernan.mtgscanner.ui.AutoBackupControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Sink
import okio.buffer
import okio.use

/**
 * Un fichero elegido por el usuario donde la app sigue escribiendo después (en Android,
 * con permiso persistente sobre su dirección; en iPhone, con un marcador de seguridad).
 */
interface BackupTarget {
    /** Conserva el permiso sobre el fichero recién elegido; devuelve lo que hay que guardar para volver a él. */
    fun keep(uri: String): String
    fun release(kept: String)
    fun displayName(kept: String): String?
    /** Para reescribirlo entero. */
    fun openSink(kept: String): Sink
}

/** Cuándo se hace la copia: en Android, cada día con WorkManager; en iPhone, al usar la app. */
interface BackupScheduler {
    /** Programar las copias ([replace]: con la configuración nueva). */
    fun schedule(replace: Boolean)
    /** Una copia en cuanto se pueda (al elegir el fichero). */
    fun runSoon()
    fun cancel()
}

/**
 * Copia automática: cada día, si la colección cambió, reescribe un fichero que el
 * usuario eligió una vez (en Google Drive, iCloud Drive, Dropbox, el móvil...). Con
 * fotos, además, un zip con el CSV y las fotos de las cartas.
 *
 * Sus preferencias no van en la copia de Android: el permiso sobre el fichero no
 * sirve en otro móvil (ver res/xml/backup_rules.xml).
 */
class AutoBackup(
    private val app: AppServices,
    private val target: BackupTarget,
    private val scheduler: BackupScheduler,
) : AutoBackupControl {
    private val prefs = platformPrefs(PREFS)
    private val mutex = Mutex()

    sealed interface Outcome {
        data class Saved(val cards: Int) : Outcome
        data object Unchanged : Outcome
        data object NoFile : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val _state = MutableStateFlow(read())
    override val state: StateFlow<AutoBackupState> = _state

    private fun read() = AutoBackupState(
        file = prefs.getString("file", null),
        fileName = prefs.getString("file_name", null),
        lastSaved = prefs.getLong("last_saved", 0),
        lastCards = prefs.getInt("last_cards", 0),
        lastChecked = prefs.getLong("last_checked", 0),
        lastError = prefs.getString("last_error", null),
        photosFile = prefs.getString("photos_file", null),
        photosFileName = prefs.getString("photos_file_name", null),
        lastPhotosSaved = prefs.getLong("last_photos_saved", 0),
        lastPhotos = prefs.getInt("last_photos", 0),
    )

    /** Al arrancar la app: que siga programada si está activa. */
    fun ensureScheduled() {
        if (_state.value.file != null) scheduler.schedule(replace = false)
    }

    /** El fichero recién creado por el usuario; lo escribe ya. */
    override fun enable(uri: String) {
        val kept = target.keep(uri)
        _state.value.file?.takeIf { it != kept }?.let { old -> runCatching { target.release(old) } }
        val photos = _state.value.photosFile
        prefs.edit().clear()
            .putString("photos_file", photos)
            .putString("photos_file_name", _state.value.photosFileName)
            .putString("file", kept)
            .putString("file_name", target.displayName(kept))
            .apply()
        _state.value = read()
        scheduler.schedule(replace = true)
        scheduler.runSoon()
    }

    /** El zip de fotos recién creado por el usuario; lo escribe ya. */
    override fun enablePhotos(uri: String) {
        val kept = target.keep(uri)
        _state.value.photosFile?.takeIf { it != kept }?.let { old -> runCatching { target.release(old) } }
        prefs.edit()
            .putString("photos_file", kept)
            .putString("photos_file_name", target.displayName(kept))
            .remove("photos_fingerprint").remove("last_photos_saved").remove("last_photos")
            .apply()
        _state.value = read()
        scheduler.runSoon()
    }

    /** Deja de copiar las fotos (el CSV sigue). */
    override fun disablePhotos() {
        _state.value.photosFile?.let { runCatching { target.release(it) } }
        prefs.edit().remove("photos_file").remove("photos_file_name").remove("photos_fingerprint")
            .remove("last_photos_saved").remove("last_photos").apply()
        _state.value = read()
    }

    override fun disable() {
        _state.value.file?.let { runCatching { target.release(it) } }
        _state.value.photosFile?.let { runCatching { target.release(it) } }
        prefs.edit().clear().apply()
        _state.value = read()
        scheduler.cancel()
    }

    /** Escribe la copia si la colección cambió desde la última ([force]: aunque no cambiara). */
    suspend fun run(force: Boolean = false): Outcome = mutex.withLock {
        val file = _state.value.file ?: return Outcome.NoFile
        val now = nowMillis()
        val outcome = runCatching {
            val content = build(photos = false)
            val photosChanged = runPhotos(force, content.fingerprint, now)
            if (!force && content.fingerprint == prefs.getString("fingerprint", null)) {
                prefs.edit().putLong("last_checked", now).remove("last_error").apply()
                return@runCatching if (photosChanged) Outcome.Saved(content.cards) else Outcome.Unchanged
            }
            withContext(Dispatchers.IO) { target.openSink(file).buffer().use { it.write(content.csv.encodeToByteArray()) } }
            prefs.edit()
                .putString("fingerprint", content.fingerprint)
                .putLong("last_saved", now).putInt("last_cards", content.cards)
                .putLong("last_checked", now).remove("last_error")
                .apply()
            Outcome.Saved(content.cards)
        }.getOrElse { e ->
            val msg = e.message ?: e::class.simpleName ?: "error"
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
        val file = _state.value.photosFile ?: return false
        val photos = app.cardPhotos ?: return false
        val fingerprint = cardsFingerprint + photos.fingerprint()
        if (!force && fingerprint == prefs.getString("photos_fingerprint", null)) return false
        val content = build(photos = true)
        withContext(Dispatchers.IO) {
            photos.zip(target.openSink(file).buffer(), ZIP_CSV_NAME, content.csv, content.photoNames)
        }
        prefs.edit().putString("photos_fingerprint", fingerprint)
            .putLong("last_photos_saved", now).putInt("last_photos", content.photoNames.size).apply()
        return true
    }

    override suspend fun runNow(): Int? = when (val r = run(force = true)) {
        is Outcome.Saved -> r.cards
        is Outcome.Failed -> error(r.message)
        else -> null
    }

    private suspend fun build(photos: Boolean) = CollectionBackup.build(
        app.catalog.await(), app.collection, app.settings, app.englishExport,
        photoIds = if (photos) app.cardPhotos?.ids() else null,
    )

    companion object {
        const val PREFS = "auto_backup"
        const val FILE_NAME = AutoBackupState.FILE_NAME
        const val PHOTOS_FILE_NAME = AutoBackupState.PHOTOS_FILE_NAME
        private const val ZIP_CSV_NAME = "coleccion.csv"
    }
}
