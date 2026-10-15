package com.hernan.mtgscanner

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import com.hernan.mtgscanner.data.AndroidFiles
import com.hernan.mtgscanner.data.AndroidBackupTarget
import com.hernan.mtgscanner.data.AutoBackup
import com.hernan.mtgscanner.data.WorkManagerBackupScheduler
import com.hernan.mtgscanner.data.CardmarketPrices
import com.hernan.mtgscanner.data.PriceUpdater
import com.hernan.mtgscanner.ui.AppServices
import com.hernan.mtgscanner.data.CardPhotos
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.openCatalog
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Settings
import com.hernan.mtgscanner.data.TestPhotos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Punto único de acceso a los datos de la app. */
class App : Application(), SingletonImageLoader.Factory, AppServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Se empieza a cargar al arrancar para que la cámara esté lista cuanto antes. */
    override lateinit var catalog: Deferred<Catalog>
        private set
    override val collection by lazy { CollectionStore(this) }
    override val settings by lazy { Settings() }
    override val prices by lazy { PriceUpdater(collection, CardmarketPrices(cacheDir.path)) }
    override val autoBackup by lazy { AutoBackup(this, AndroidBackupTarget(this), WorkManagerBackupScheduler(this)) }
    override val files by lazy { AndroidFiles(this) }
    /** Herramienta de depuración: solo en las builds de depuración, no en la versión publicada. */
    override val testPhotos by lazy {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) TestPhotos(java.io.File(noBackupFilesDir, "test_photos").path, files, collection::entriesNow) else null
    }
    override val cardPhotos by lazy { CardPhotos(java.io.File(noBackupFilesDir, "card_photos").path) }

    /** Cabeceras del CSV en inglés salvo con la app en español (el importador entiende ambas). */
    override val englishExport get() = (settings.appLanguage.value ?: resources.configuration.locales[0].language) != "es"

    override fun onCreate() {
        super.onCreate()
        com.hernan.mtgscanner.data.appContext = this
        catalog = scope.async { openCatalog(this@App) }
        autoBackup.ensureScheduled()
        // Fotos de cartas que se borraron (en la sesión anterior: hasta salir se pueden recuperar con "Deshacer").
        scope.launch { runCatching { cardPhotos.removeOrphans(collection.allEntryIds()) } }
        // Valor según el estado: la colección lo aplica al calcular totales y filas.
        scope.launch {
            combine(settings.conditionPricing, settings.conditionFactors) { on, f -> f.takeIf { on } }
                .collect { collection.conditionFactors = it }
        }
    }

    /**
     * Imágenes de cartas: Scryfall rechaza (400) el User-Agent por defecto de OkHttp,
     * así que se identifica la app igual que en las consultas de precios.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = {
                HttpClient { defaultRequest { header("User-Agent", USER_AGENT) } }
            }))
        }
        .crossfade(true)
        .build()

    companion object {
        const val USER_AGENT = com.hernan.mtgscanner.data.USER_AGENT
    }
}
