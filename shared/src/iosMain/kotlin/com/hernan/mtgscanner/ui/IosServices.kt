package com.hernan.mtgscanner.ui

import com.hernan.mtgscanner.data.AutoBackup
import com.hernan.mtgscanner.data.CardPhotos
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.IosBackupScheduler
import com.hernan.mtgscanner.data.IosBackupTarget
import com.hernan.mtgscanner.data.PriceUpdater
import com.hernan.mtgscanner.data.Settings
import com.hernan.mtgscanner.data.TestPhotos
import com.hernan.mtgscanner.data.openCardmarketGuide
import com.hernan.mtgscanner.data.openCatalog
import com.hernan.mtgscanner.data.openCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.preferredLanguages

/** Los datos y servicios de la app de iPhone (lo mismo que App en Android). */
class IosServices : AppServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val catalog: Deferred<Catalog> = scope.async {
        val path = NSBundle.mainBundle.pathForResource("catalog", ofType = "sqlite") ?: error("El catálogo no está en la app")
        openCatalog(path)
    }
    override val collection: CollectionStore by lazy { openCollection() }
    override val settings: Settings by lazy { Settings() }
    override val prices: PriceUpdater by lazy { PriceUpdater(collection, openCardmarketGuide(dir(NSCachesDirectory))) }
    override val files: PlatformFiles = IosFiles()
    // Las fotos van en Application Support: no es la carpeta que se ve en Archivos.
    override val cardPhotos: CardPhotos by lazy { CardPhotos("${dir(NSApplicationSupportDirectory)}/card_photos") }
    // Las fotos de prueba son una herramienta de depuración: no van en la app publicada.
    override val testPhotos: TestPhotos? = null
    override val autoBackup: AutoBackup by lazy {
        AutoBackup(this, IosBackupTarget(), IosBackupScheduler(scope, collection) { autoBackup })
    }

    override val englishExport: Boolean
        get() = (settings.appLanguage.value ?: NSLocale.preferredLanguages.firstOrNull() as? String)?.startsWith("es") != true

    init {
        // Valor según el estado: la colección lo aplica al calcular totales y filas (como App en Android).
        scope.launch {
            combine(settings.conditionPricing, settings.conditionFactors) { on, f -> f.takeIf { on } }
                .collect { collection.conditionFactors = it }
        }
        // Fotos de cartas que se borraron (como en Android, al arrancar).
        scope.launch { runCatching { cardPhotos.removeOrphans(collection.allEntryIds()) } }
        autoBackup.ensureScheduled()
    }

    private fun dir(kind: ULong): String =
        NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String
}
