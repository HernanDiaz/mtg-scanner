package com.hernan.mtgscanner

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hernan.mtgscanner.data.AutoBackup
import com.hernan.mtgscanner.data.CardPhotos
import com.hernan.mtgscanner.data.Catalog
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
import okhttp3.OkHttpClient

/** Punto único de acceso a los datos de la app. */
class App : Application(), ImageLoaderFactory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Se empieza a cargar al arrancar para que la cámara esté lista cuanto antes. */
    lateinit var catalog: Deferred<Catalog>
        private set
    val collection by lazy { CollectionStore(this) }
    val settings by lazy { Settings(this) }
    val autoBackup by lazy { AutoBackup(this) }
    val testPhotos by lazy { TestPhotos(java.io.File(noBackupFilesDir, "test_photos")) }
    val cardPhotos by lazy { CardPhotos(java.io.File(noBackupFilesDir, "card_photos")) }

    override fun onCreate() {
        super.onCreate()
        catalog = scope.async { Catalog(this@App) }
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
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build()
        }
        .crossfade(true)
        .build()

    companion object {
        const val USER_AGENT = "MTGScanner/0.1"
    }
}
