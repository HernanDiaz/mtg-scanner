package com.hernan.mtgscanner.ui

import com.hernan.mtgscanner.data.CollectionGroup
import android.app.Application
import android.content.res.Resources
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.App
import com.hernan.mtgscanner.R
import com.hernan.mtgscanner.data.AutoBackup
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionBackup
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionImport
import com.hernan.mtgscanner.data.CollectionFilter
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Legality
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.CardmarketPrices
import com.hernan.mtgscanner.data.PriceKind
import com.hernan.mtgscanner.data.PriceUpdater
import com.hernan.mtgscanner.scan.ArtSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CollectionUi(
    val groups: List<CollectionGroup> = emptyList(),
    /** Totales de lo que se está viendo (toda la colección, un mazo, un color...). */
    val summary: CollectionStore.Summary = CollectionStore.Summary(0, 0.0, 0),
    /** Pendientes de revisar en toda la colección (el botón de revisar no depende del filtro). */
    val pendingTotal: Int = 0,
    val currency: Currency = Currency.EUR,
    /** Precio de Cardmarket con que se valora (en euros). */
    val priceKind: PriceKind = PriceKind.TREND,
    val filter: CollectionFilter = CollectionFilter(),
    val sort: CollectionStore.Sort = CollectionStore.Sort.RECENT,
    val decks: List<CollectionStore.Deck> = emptyList(),
    val updatingPrices: Boolean = false,
    /** Ya se ha leído la colección (antes, la lista vacía no significa que no haya cartas). */
    val loaded: Boolean = false,
    /** Con un mazo abierto: formato -> cartas que impiden jugarlo en él (0 = legal). */
    val deckFormats: Map<String, Int> = emptyMap(),
    val message: String? = null,
    /** El mensaje es de un borrado que se puede deshacer. */
    val canUndoDelete: Boolean = false,
) {
    /** "Toda la colección", "Mazo Goblins · Rojo"... para títulos y exportaciones. */
    fun scopeLabel(res: Resources): String {
        val deck = when (filter.deckId) {
            null -> null
            CollectionStore.NO_DECK -> res.getString(R.string.no_deck)
            else -> decks.firstOrNull { it.id == filter.deckId }?.let { res.getString(R.string.scope_deck, it.name) }
        }
        val colors = filter.colors.takeIf { it.isNotEmpty() }?.let { set ->
            COLOR_NAMES.filterKeys { it in set }.values.joinToString(res.getString(R.string.colors_or)) { res.getString(it) }
        }
        return listOfNotNull(deck, colors, filter.format?.let(Legality::displayName),
            filter.query.takeIf { it.isNotBlank() }?.let { "“$it”" })
            .joinToString(" · ").ifEmpty { res.getString(R.string.whole_collection) }
    }
}

/** Nombres de los colores del filtro, en el orden habitual de Magic. */
val COLOR_NAMES: Map<Char, Int> = linkedMapOf(
    'W' to R.string.color_white, 'U' to R.string.color_blue, 'B' to R.string.color_black,
    'R' to R.string.color_red, 'G' to R.string.color_green, 'C' to R.string.color_colorless,
)

/**
 * Un fichero leído y listo para importar, a falta de que el usuario elija
 * añadirlo o sustituir la colección.
 */
data class ImportPreview(
    val fileName: String,
    val result: CollectionImport.Result,
    /** Lo que hay ahora, para avisar de lo que se borra al sustituir. */
    val currentCards: Int,
    val currentDecks: Int,
    /** Mazo que se está viendo: al añadir, las cartas del fichero sin mazo van a él. */
    val targetDeck: CollectionStore.Deck?,
)

/** Periodo de la gráfica de evolución. */
enum class HistoryRange(val days: Int?, @androidx.annotation.StringRes val label: Int) {
    WEEK(7, R.string.range_week), MONTH(30, R.string.range_month), YEAR(365, R.string.range_year), ALL(null, R.string.range_all),
}

class ValueHistory(
    val points: List<CollectionStore.ValuePoint>,
    val up: List<CollectionStore.Mover>,
    val down: List<CollectionStore.Mover>,
)

/** Una edición posible en el selector, con su parecido a la foto (0-1) si se conoce. */
data class PrintingOption(val printing: CardPrinting, val similarity: Double?)

class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val store = app.collection
    private val settings = app.settings
    private val updater = PriceUpdater(store, CardmarketPrices(application.cacheDir))

    private val filter = MutableStateFlow(CollectionFilter())
    private val sort = MutableStateFlow(CollectionStore.Sort.RECENT)
    private val _ui = MutableStateFlow(CollectionUi())
    val ui: StateFlow<CollectionUi> = _ui

    init {
        viewModelScope.launch {
            combine(store.version, settings.currency, filter, sort, settings.priceKind) { _, c, f, s, k ->
                Query(c, f, s, k)
            }
                .collect { (currency, f, s, kind) ->
                    val result = withContext(Dispatchers.IO) {
                        Loaded(
                            groups = store.groups(currency, f, s, kind),
                            summary = store.summary(currency, f, kind),
                            pendingTotal = store.summary(currency).pendingReview,
                            decks = store.decks(),
                            deckFormats = if ((f.deckId ?: CollectionStore.NO_DECK) != CollectionStore.NO_DECK) {
                                store.formatProblems(f)
                            } else emptyMap(),
                        )
                    }
                    // El valor de hoy para la gráfica de evolución (con cada cambio: cartas o precios).
                    withContext(Dispatchers.IO) { store.recordValue(CollectionStore.day()) }
                    _ui.update {
                        it.copy(groups = result.groups, summary = result.summary, pendingTotal = result.pendingTotal,
                            decks = result.decks, currency = currency, filter = f, sort = s, priceKind = kind, loaded = true,
                            deckFormats = result.deckFormats)
                    }
                }
        }
        // Las cartas escaneadas con versiones anteriores no guardaban sus colores.
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = app.catalog.await()
            store.backfillColors { id -> catalog.byId(id)?.colors }
            store.backfillCardmarketIds { id -> catalog.byId(id)?.cardmarketId }
            store.updateLegality(catalog::legalityOf)
        }
        // Precios del día: si los más antiguos tienen más de 24 h, se actualizan solos.
        viewModelScope.launch {
            val oldest = withContext(Dispatchers.IO) { store.oldestPriceUpdate() }
            if (oldest != null && System.currentTimeMillis() - oldest > DAY_MS) updatePrices()
        }
    }

    private data class Query(
        val currency: Currency, val filter: CollectionFilter, val sort: CollectionStore.Sort, val kind: PriceKind,
    )

    private class Loaded(
        val groups: List<CollectionGroup>,
        val summary: CollectionStore.Summary,
        val pendingTotal: Int,
        val decks: List<CollectionStore.Deck>,
        val deckFormats: Map<String, Int>,
    )

    // ------------------------------------------------------------------ filtros

    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun setOnlyPending(v: Boolean) = filter.update { it.copy(onlyPending = v) }
    fun setSort(s: CollectionStore.Sort) { sort.value = s }
    /** null = toda la colección. */
    fun showDeck(deckId: Long?) = filter.update { it.copy(deckId = deckId) }
    /** null = todos los formatos. */
    fun setFormat(format: String?) = filter.update { it.copy(format = format) }
    fun toggleColor(c: Char) = filter.update { f -> f.copy(colors = if (c in f.colors) f.colors - c else f.colors + c) }

    // ------------------------------------------------------------------ mazos

    fun createDeck(name: String, andShow: Boolean = true) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = store.createDeck(name)
            if (andShow) showDeck(id)
        }
    }

    suspend fun createDeckNow(name: String): Long = withContext(Dispatchers.IO) { store.createDeck(name) }

    fun renameDeck(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { store.renameDeck(id, name) }
    }

    fun deleteDeck(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteDeck(id)
            if (filter.value.deckId == id) showDeck(null)
        }
    }

    fun moveToDeck(group: CollectionGroup, deckId: Long?, count: Int) {
        viewModelScope.launch(Dispatchers.IO) { store.moveToDeck(group, deckId, count) }
    }

    // ------------------------------------------------------------------ cartas

    /** [quiet]: sin mensaje al terminar bien (tras importar, para no tapar el resultado). */
    fun updatePrices(quiet: Boolean = false) {
        if (_ui.value.updatingPrices) return
        _ui.update { it.copy(updatingPrices = true, message = if (quiet) it.message else null) }
        viewModelScope.launch {
            // La guía de Cardmarket (~26 MB) solo hace falta para el precio más bajo o la media.
            val guide = settings.priceKind.value != PriceKind.TREND
            val msg = runCatching { updater.updateAll(withCardmarketGuide = guide) }.fold(
                onSuccess = { if (quiet) null else app.getString(R.string.prices_updated, it) },
                onFailure = { app.getString(R.string.prices_failed, it.message) },
            )
            _ui.update { it.copy(updatingPrices = false, message = msg ?: it.message) }
        }
    }

    fun setCurrency(c: Currency) = settings.setCurrency(c)

    // Valor según el estado
    val conditionPricing get() = settings.conditionPricing
    val conditionFactors get() = settings.conditionFactors
    fun setConditionPricing(on: Boolean) = settings.setConditionPricing(on)
    fun setConditionFactor(condition: String, percent: Int) = settings.setConditionFactor(condition, percent)
    fun resetConditionFactors() = settings.resetConditionFactors()

    /** Al elegir el precio más bajo o la media por primera vez hace falta descargar la guía. */
    fun setPriceKind(k: PriceKind) {
        if (k == settings.priceKind.value) return
        settings.setPriceKind(k)
        if (k != PriceKind.TREND && _ui.value.summary.cards + _ui.value.pendingTotal > 0) updatePrices()
    }

    /** Lo último borrado de golpe, para "Deshacer" mientras se ve el mensaje. */
    private var lastDeleted: List<ContentValues>? = null

    /** Borra todas las copias de estas filas (varias seleccionadas o todo lo que se ve). */
    fun deleteGroups(groups: List<CollectionGroup>) {
        if (groups.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val rows = store.deleteGroups(groups)
            lastDeleted = rows
            _ui.update { it.copy(message = app.getString(R.string.deleted_cards, app.cardsLabel(rows.size)), canUndoDelete = true) }
        }
    }

    fun undoDelete() {
        val rows = lastDeleted ?: return
        lastDeleted = null
        _ui.update { it.copy(canUndoDelete = false) }
        viewModelScope.launch(Dispatchers.IO) {
            store.restore(rows)
            _ui.update { it.copy(message = app.getString(R.string.restored_cards, app.cardsLabel(rows.size))) }
        }
    }

    /** Mueve todas las copias de estas filas a un mazo; con [newDeckName], a uno nuevo. */
    fun moveGroups(groups: List<CollectionGroup>, deckId: Long?, newDeckName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = newDeckName?.takeIf { it.isNotBlank() }?.let { store.createDeck(it.trim()) } ?: deckId
            store.moveGroupsToDeck(groups, target)
        }
    }

    fun removeOne(group: CollectionGroup) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(group.lastEntryId) }
    }

    private fun pendingFilter() = CollectionFilter(onlyPending = true)

    /** Primera fila pendiente de revisar (para el modo revisión), o null si no queda ninguna. */
    suspend fun firstPending(): CollectionGroup? = withContext(Dispatchers.IO) {
        store.groups(_ui.value.currency, pendingFilter()).firstOrNull()
    }

    /** Asigna la edición (o confirma la sugerida) y devuelve la siguiente pendiente. */
    suspend fun reassignAndNext(group: CollectionGroup, printing: CardPrinting): CollectionGroup? =
        withContext(Dispatchers.IO) {
            store.reassign(group, printing)
            store.groups(_ui.value.currency, pendingFilter()).firstOrNull()
        }

    suspend fun confirmAndNext(group: CollectionGroup): CollectionGroup? =
        withContext(Dispatchers.IO) {
            store.confirm(group)
            store.groups(_ui.value.currency, pendingFilter()).firstOrNull()
        }

    /** Añadir a mano; si se está viendo un mazo, va a ese mazo. */
    fun addManual(printing: CardPrinting, foil: Boolean, lang: String) {
        val deck = filter.value.deckId?.takeIf { it != CollectionStore.NO_DECK }
        viewModelScope.launch(Dispatchers.IO) {
            store.add(printing, foil && printing.hasFoil || !printing.hasNonfoil,
                settings.defaultCondition.value, needsReview = false, lang = lang, deckId = deck)
            if (store.fulfillWish(printing.name)) showMessage(app.getString(R.string.wish_fulfilled, printing.name))
        }
    }

    fun setLanguage(group: CollectionGroup, lang: String) {
        viewModelScope.launch(Dispatchers.IO) { store.setLanguage(group, lang) }
    }

    fun setCondition(group: CollectionGroup, condition: String) {
        viewModelScope.launch(Dispatchers.IO) { store.setCondition(group, condition) }
    }

    fun setFoil(group: CollectionGroup, foil: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { store.setFoil(group, foil) }
    }

    // ------------------------------------------------------------------ evolución del valor

    /** Valor de la colección y cartas que más han cambiado de precio en el periodo. */
    suspend fun history(range: HistoryRange): ValueHistory = withContext(Dispatchers.IO) {
        val ui = _ui.value
        val since = range.days?.let { CollectionStore.day(System.currentTimeMillis() - it * DAY_MS) }
        val points = store.valueHistory(ui.currency, ui.priceKind, since)
        // Los cambios de precio desde el primer día del periodo con datos.
        val movers = store.movers(ui.currency, ui.priceKind, since ?: points.firstOrNull()?.day ?: CollectionStore.day())
        ValueHistory(
            points = points,
            up = movers.filter { it.change > 0 }.sortedByDescending { it.change * it.quantity }.take(MOVERS),
            down = movers.filter { it.change < 0 }.sortedBy { it.change * it.quantity }.take(MOVERS),
        )
    }

    // ------------------------------------------------------------------ ficha de carta

    suspend fun printingOf(id: String): CardPrinting? = withContext(Dispatchers.IO) { app.catalog.await().byId(id) }

    suspend fun cardSheet(id: String, name: String, group: CollectionGroup? = null): CardSheet =
        withContext(Dispatchers.IO) {
            CardSheet(store.storedPrices(id), store.copiesOf(name), group?.let { app.cardPhotos.of(store.entryIds(it)) }.orEmpty())
        }

    fun addWish(p: CardPrinting) {
        viewModelScope.launch(Dispatchers.IO) {
            store.addWish(p, (store.wishlist(Currency.EUR).firstOrNull { it.name == p.name }?.quantity ?: 0) + 1)
            showMessage(app.getString(R.string.added_to_wishlist, p.name))
        }
    }

    /** Texto, legalidad y enlaces de Scryfall; null sin conexión. */
    suspend fun cardDetails(id: String): CardDetails? =
        withContext(Dispatchers.IO) { runCatching { CardDetails.fetch(id) }.getOrNull() }


    /**
     * Ediciones posibles de una carta. Si se guardó la ilustración fotografiada,
     * ordenadas por parecido (las del mismo dibujo primero); si no, de la más
     * reciente a la más antigua.
     */
    suspend fun optionsFor(name: String, photoArt: ByteArray?): List<PrintingOption> = withContext(Dispatchers.Default) {
        val catalog = app.catalog.await()
        val printings = catalog.printingsOf(name)
        val photo = photoArt?.let(ArtSignature::fromPacked) ?: return@withContext printings.map { PrintingOption(it, null) }
        val byArt = HashMap<Int, Double>()
        printings.map { p ->
            val sim = p.artId?.let { id -> byArt.getOrPut(id) { catalog.art(id)?.let(photo::similarity) ?: -1.0 } }
            PrintingOption(p, sim?.takeIf { it >= 0 })
        }.sortedByDescending { it.similarity ?: -1.0 }
    }

    // ------------------------------------------------------------------ exportar

    /**
     * Exporta lo que se está viendo (toda la colección, un mazo, un color...) y
     * devuelve el Intent para compartirlo. Los CSV van como fichero; la lista, como texto.
     */
    suspend fun exportIntent(format: CollectionExport.Format, photos: Boolean = false): Intent = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val ui = _ui.value
        val detailed = format == CollectionExport.Format.CSV
        val plain = CollectionExport.lines(store.groups(ui.currency, ui.filter, kind = ui.priceKind), catalog::setName, ::deckName,
            detailed = detailed)
        val withPhoto = if (photos) app.cardPhotos.of(store.allEntryIds()).mapNotNull { it.name.substringBefore('.').toLongOrNull() }.toSet() else emptySet()
        val (lines, photoNames) = if (photos) {
            CollectionExport.withPhotos(plain, store.entryKeys(), detailed, ::deckName, withPhoto::contains)
        } else {
            plain to emptyMap()
        }
        val content = CollectionExport.render(format, lines, ui.currency, english = isEnglish())
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val scope = ui.scopeLabel(app.resources)
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, app.getString(R.string.share_subject, scope, date))
        if (photos) {
            // Zip con el CSV (que nombra las fotos de cada línea) y las fotos.
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() } // los de otras veces
            val file = File(dir, "${fileSafe(scope)}-$date.zip")
            file.outputStream().use { out ->
                app.cardPhotos.zip(out, "${fileSafe(scope)}-$date.${format.extension}", Char(0xFEFF) + content, photoNames)
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            send.setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (format == CollectionExport.Format.TEXT) {
            send.setType(format.mime).putExtra(Intent.EXTRA_TEXT, content)
        } else {
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            val suffix = if (format == CollectionExport.Format.MOXFIELD) "-moxfield" else ""
            val file = File(dir, "${fileSafe(scope)}-$date$suffix.${format.extension}")
            // BOM para que Excel abra bien las tildes.
            file.writeText(Char(0xFEFF) + content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            send.setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, app.getString(R.string.share_title, scope))
    }

    private fun deckName(id: Long?) = id?.let { d -> _ui.value.decks.firstOrNull { it.id == d }?.name }

    // ------------------------------------------------------------------ copia de seguridad e importar

    // Fotos de prueba (ver TestPhotos).
    val testPhotosEnabled: StateFlow<Boolean> = settings.testPhotos

    fun setTestPhotos(on: Boolean) = settings.setTestPhotos(on)

    /** Cuántas fotos hay y cuánto ocupan. */
    suspend fun testPhotosInfo(): Pair<Int, Long> = withContext(Dispatchers.IO) { app.testPhotos.count() to app.testPhotos.size() }

    /** Zip con las fotos y su respuesta correcta (la carta tal como está ahora), para compartirlo. */
    suspend fun exportTestPhotos(): Intent = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(TEST_PHOTOS_PREFIX) }?.forEach { it.delete() } // los de otras veces
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())
        val file = File(dir, "$TEST_PHOTOS_PREFIX-$stamp.zip")
        app.testPhotos.export(file, store::entriesNow)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, app.getString(R.string.test_photos_share))
    }

    suspend fun clearTestPhotos() = withContext(Dispatchers.IO) { app.testPhotos.clear() }

    // Fotos de las cartas (ver CardPhotos).
    val cardPhotosEnabled: StateFlow<Boolean> = settings.cardPhotos

    fun setCardPhotos(on: Boolean) = settings.setCardPhotos(on)

    /** Cuántas fotos hay y cuánto ocupan. */
    suspend fun cardPhotosInfo(): Pair<Int, Long> = withContext(Dispatchers.IO) { app.cardPhotos.count() to app.cardPhotos.size() }

    suspend fun clearCardPhotos() = withContext(Dispatchers.IO) { app.cardPhotos.clear() }

    // Copia automática (ver AutoBackup).
    val autoBackup: StateFlow<AutoBackup.State> = app.autoBackup.state

    fun enableAutoBackup(uri: Uri) = runCatching { app.autoBackup.enable(uri) }
        .onFailure { e -> _ui.update { it.copy(message = app.getString(R.string.backup_failed, e.message)) } }

    fun disableAutoBackup() = app.autoBackup.disable()

    fun enableAutoBackupPhotos(uri: Uri) = runCatching { app.autoBackup.enablePhotos(uri) }
        .onFailure { e -> _ui.update { it.copy(message = app.getString(R.string.backup_failed, e.message)) } }

    fun disableAutoBackupPhotos() = app.autoBackup.disablePhotos()

    fun autoBackupNow() {
        viewModelScope.launch {
            val msg = when (val r = app.autoBackup.run(force = true)) {
                is AutoBackup.Outcome.Saved -> app.getString(R.string.backup_saved, app.cardsLabel(r.cards))
                is AutoBackup.Outcome.Failed -> app.getString(R.string.backup_failed, r.message)
                else -> null
            }
            msg?.let { m -> _ui.update { it.copy(message = m) } }
        }
    }

    fun backupFileName() =
        "${app.getString(R.string.backup_file_prefix)}-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.csv"

    /** Exportar con cabeceras en inglés salvo con la app en español (el importador entiende ambas). */
    private fun isEnglish() = app.resources.configuration.locales[0].language != "es"

    /** Copia de seguridad ([CollectionBackup]) en el sitio que elija el usuario (Drive, Descargas...). */
    fun writeBackup(uri: Uri) {
        viewModelScope.launch {
            val msg = runCatching {
                val content = CollectionBackup.build(app)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content.csv.toByteArray(Charsets.UTF_8)) }
                }
                content.cards
            }.fold(
                onSuccess = { app.getString(R.string.backup_saved, app.cardsLabel(it)) },
                onFailure = { app.getString(R.string.backup_failed, it.message) },
            )
            _ui.update { it.copy(message = msg) }
        }
    }

    /** Lee un fichero y busca sus cartas en el catálogo, sin guardar nada todavía. */
    suspend fun readImport(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val bytes = app.contentResolver.openInputStream(uri)!!.use { input ->
            val data = input.readBytes()
            require(data.size <= MAX_IMPORT_BYTES) { app.getString(R.string.file_too_big) }
            data
        }
        val rows = CollectionImport.parse(CollectionImport.decode(bytes))
        val result = CollectionImport.resolve(rows, catalog.importLookup, settings.defaultCondition.value)
        val ui = _ui.value
        ImportPreview(
            fileName = displayName(uri) ?: app.getString(R.string.file_default_name),
            result = result,
            currentCards = store.summary(ui.currency).cards,
            currentDecks = store.decks().size,
            targetDeck = ui.decks.firstOrNull { it.id == ui.filter.deckId },
        )
    }

    /** Guarda lo leído: añadiéndolo a la colección o sustituyéndola entera. */
    fun applyImport(preview: ImportPreview, replace: Boolean) {
        viewModelScope.launch {
            val msg = runCatching {
                withContext(Dispatchers.IO) {
                    store.import(preview.result.items, replace, defaultDeck = preview.targetDeck?.id)
                }
            }.fold(
                onSuccess = { app.getString(if (replace) R.string.collection_replaced else R.string.cards_added, app.cardsLabel(it)) },
                onFailure = { app.getString(R.string.import_failed, it.message) },
            )
            if (replace) filter.value = CollectionFilter()
            _ui.update { it.copy(message = msg) }
            // Los precios del catálogo son los de cuando se hizo la app.
            if (preview.result.items.isNotEmpty()) updatePrices(quiet = true)
        }
    }

    private fun displayName(uri: Uri): String? =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** "Mazo Goblins · Rojo" -> "mazo-goblins-rojo" (nombre de fichero). */
    private fun fileSafe(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "coleccion" }.take(60)

    suspend fun searchNames(query: String): List<String> =
        withContext(Dispatchers.Default) { app.catalog.await().names.search(query) }

    fun clearMessage() {
        lastDeleted = null
        _ui.update { it.copy(message = null, canUndoDelete = false) }
    }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        const val MOVERS = 10
    }
}

private const val TEST_PHOTOS_PREFIX = "mtg-fotos-prueba"
