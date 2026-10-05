package com.hernan.mtgscanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.hernan.mtgscanner.data.AutoBackupState
import com.hernan.mtgscanner.data.CardPhotos
import com.hernan.mtgscanner.data.TestPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.PriceUpdater
import com.hernan.mtgscanner.data.Settings
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que las pantallas comunes necesitan de la app: los datos (comunes) y lo que cada
 * plataforma hace a su manera (ficheros, compartir, fotos, copia automática). Lo que una
 * plataforma aún no tiene es null y su opción no se muestra.
 */
interface AppServices {
    /** Se empieza a cargar al arrancar (~1 s). */
    val catalog: Deferred<Catalog>
    val collection: CollectionStore
    val settings: Settings
    val prices: PriceUpdater
    /** Cabeceras del CSV en inglés salvo con la app en español (el importador entiende ambas). */
    val englishExport: Boolean
    val files: PlatformFiles
    val cardPhotos: CardPhotos?
    val testPhotos: TestPhotos?
    val autoBackup: AutoBackupControl?
}

val LocalServices = staticCompositionLocalOf<AppServices> { error("Falta LocalServices") }

/** Ficheros elegidos por el usuario (por su dirección en el sistema) y compartir. */
interface PlatformFiles {
    /** Contenido de un fichero elegido con [rememberOpenDocument]; falla si pasa de [maxBytes]. */
    suspend fun read(uri: String, maxBytes: Int): ByteArray
    /** Nombre visible de un fichero elegido, si se conoce. */
    fun displayName(uri: String): String?
    /** Reescribe un fichero creado con [rememberCreateDocument]. */
    suspend fun write(uri: String, bytes: ByteArray)
    /** Ruta de un fichero nuevo para compartir (en la caché; borra los anteriores con la misma extensión). */
    fun exportPath(name: String): String
    /** Abre el menú de compartir del sistema con el fichero [path]. */
    fun sharePath(path: String, mime: String, subject: String?, title: String)
    /** Lo mismo con un texto (listas de cartas). */
    fun shareText(text: String, subject: String, title: String)
}

/** Escribe [bytes] en un fichero [name] y lo comparte. */
suspend fun PlatformFiles.shareBytes(name: String, mime: String, bytes: ByteArray, subject: String, title: String) {
    val path = exportPath(name)
    withContext(Dispatchers.IO) { FileSystem.SYSTEM.write(path.toPath()) { write(bytes) } }
    sharePath(path, mime, subject, title)
}

/** Copia automática diaria a un fichero elegido por el usuario (ver AutoBackup en Android). */
interface AutoBackupControl {
    val state: StateFlow<AutoBackupState>
    fun enable(uri: String)
    fun disable()
    fun enablePhotos(uri: String)
    fun disablePhotos()
    /** Copia ya; devuelve las cartas copiadas, o lanza el error. null si no hay fichero. */
    suspend fun runNow(): Int?
}

/**
 * Elegir un fichero para leerlo (importar, comprobar un mazo). Devuelve la función que
 * abre el selector; [onPicked] recibe la dirección del fichero.
 */
@Composable
expect fun rememberOpenDocument(onPicked: (String) -> Unit): () -> Unit

/**
 * Crear un fichero donde elija el usuario (Drive, Descargas...) para escribirlo después.
 * Devuelve la función que abre el selector con un nombre propuesto.
 */
@Composable
expect fun rememberCreateDocument(mime: String, onCreated: (String) -> Unit): (suggestedName: String) -> Unit

/** El botón Atrás del sistema (en iPhone no hay: no hace nada). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
