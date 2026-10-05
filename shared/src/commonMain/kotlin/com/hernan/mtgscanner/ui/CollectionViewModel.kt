package com.hernan.mtgscanner.ui

import com.hernan.mtgscanner.data.CardmarketGuide
import com.hernan.mtgscanner.data.CollectionGroup
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.data.AutoBackupState
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionBackup
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionImport
import com.hernan.mtgscanner.data.CollectionFilter
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Legality
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.ContentValues
import com.hernan.mtgscanner.data.PriceKind
import com.hernan.mtgscanner.data.localDay
import com.hernan.mtgscanner.data.nfkd
import com.hernan.mtgscanner.data.nowMillis
import com.hernan.mtgscanner.scan.ArtSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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
    /** "Toda la colección", "Mazo Goblins · Rojo"... para títulos. */
    @Composable
    fun scopeLabel(): String {
        val texts = SCOPE_TEXTS.associateWith { stringResource(it) }
        val deck = decks.firstOrNull { it.id == filter.deckId }?.let { stringResource(Res.string.scope_deck, it.name) }
        return scopeLabel({ texts.getValue(it) }, deck)
    }

    /** Lo mismo fuera de la composición (nombres de ficheros exportados). */
    suspend fun scopeText(): String {
        val texts = SCOPE_TEXTS.associateWith { getString(it) }
        val deck = decks.firstOrNull { it.id == filter.deckId }?.let { getString(Res.string.scope_deck, it.name) }
        return scopeLabel({ texts.getValue(it) }, deck)
    }

    private fun scopeLabel(text: (StringResource) -> String, deckLabel: String?): String {
        val deck = when (filter.deckId) {
            null -> null
            CollectionStore.NO_DECK -> text(Res.string.no_deck)
            else -> deckLabel
        }
        val colors = filter.colors.takeIf { it.isNotEmpty() }?.let { set ->
            COLOR_NAMES.filterKeys { it in set }.values.joinToString(text(Res.string.colors_or)) { text(it) }
        }
        return listOfNotNull(deck, colors, filter.format?.let(Legality::displayName),
            filter.query.takeIf { it.isNotBlank() }?.let { "“$it”" })
            .joinToString(" · ").ifEmpty { text(Res.string.whole_collection) }
    }
}

/** Textos fijos de [CollectionUi.scopeLabel]. */
private val SCOPE_TEXTS: List<StringResource> by lazy {
    listOf(Res.string.no_deck, Res.string.colors_or, Res.string.whole_collection) + COLOR_NAMES.values
}

/** Nombres de los colores del filtro, en el orden habitual de Magic. */
val COLOR_NAMES: Map<Char, StringResource> by lazy {
    linkedMapOf(
        'W' to Res.string.color_white, 'U' to Res.string.color_blue, 'B' to Res.string.color_black,
        'R' to Res.string.color_red, 'G' to Res.string.color_green, 'C' to Res.string.color_colorless,
    )
}

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
enum class HistoryRange(val days: Int?) {
    WEEK(7), MONTH(30), YEAR(365), ALL(null);

    val label: StringResource
        get() = when (this) {
            WEEK -> Res.string.range_week
            MONTH -> Res.string.range_month
            YEAR -> Res.string.range_year
            ALL -> Res.string.range_all
        }
}

class ValueHistory(
    val points: List<CollectionStore.ValuePoint>,
    val up: List<CollectionStore.Mover>,
    val down: List<CollectionStore.Mover>,
)

/** Una edición posible en el selector, con su parecido a la foto (0-1) si se conoce. */
data class PrintingOption(val printing: CardPrinting, val similarity: Double?)

class CollectionViewModel(private val app: AppServices) : ViewModel() {
    private val store = app.collection
    private val settings = app.settings
    private val updater = app.prices

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
            if (oldest != null && nowMillis() - oldest > DAY_MS) updatePrices()
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
                onSuccess = { if (quiet) null else getString(Res.string.prices_updated, it) },
                onFailure = { getString(Res.string.prices_failed, it.message.orEmpty()) },
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
            val msg = getString(Res.string.deleted_cards, cardsText(rows.size))
            _ui.update { it.copy(message = msg, canUndoDelete = true) }
        }
    }

    fun undoDelete() {
        val rows = lastDeleted ?: return
        lastDeleted = null
        _ui.update { it.copy(canUndoDelete = false) }
        viewModelScope.launch(Dispatchers.IO) {
            store.restore(rows)
            val msg = getString(Res.string.restored_cards, cardsText(rows.size))
            _ui.update { it.copy(message = msg) }
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
            if (store.fulfillWish(printing.name)) showMessage(getString(Res.string.wish_fulfilled, printing.name))
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
        val since = range.days?.let { CollectionStore.day(nowMillis() - it * DAY_MS) }
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
            CardSheet(store.storedPrices(id), store.copiesOf(name), group?.let { g -> app.cardPhotos?.of(store.entryIds(g)) }.orEmpty())
        }

    fun addWish(p: CardPrinting) {
        viewModelScope.launch(Dispatchers.IO) {
            store.addWish(p, (store.wishlist(Currency.EUR).firstOrNull { it.name == p.name }?.quantity ?: 0) + 1)
            showMessage(getString(Res.string.added_to_wishlist, p.name))
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
     * Exporta lo que se está viendo (toda la colección, un mazo, un color...) y abre el
     * menú de compartir. Los CSV van como fichero; la lista, como texto; con [photos], un
     * zip con el CSV y las fotos de las cartas.
     */
    suspend fun export(format: CollectionExport.Format, photos: Boolean = false) {
        val (content, photoNames) = withContext(Dispatchers.IO) {
            val catalog = app.catalog.await()
            val ui = _ui.value
            val detailed = format == CollectionExport.Format.CSV
            val plain = CollectionExport.lines(store.groups(ui.currency, ui.filter, kind = ui.priceKind), catalog::setName, ::deckName,
                detailed = detailed)
            val photoIds = if (photos) app.cardPhotos?.ids().orEmpty() else emptySet()
            val (lines, names) = if (photos) {
                CollectionExport.withPhotos(plain, store.entryKeys(), detailed, ::deckName, photoIds::contains)
            } else {
                plain to emptyMap()
            }
            CollectionExport.render(format, lines, ui.currency, english = app.englishExport) to names
        }
        val date = localDay(nowMillis())
        val scope = _ui.value.scopeText()
        val subject = getString(Res.string.share_subject, scope, date)
        val title = getString(Res.string.share_title, scope)
        val cardPhotos = app.cardPhotos
        when {
            photos && cardPhotos != null -> {
                val path = app.files.exportPath("${fileSafe(scope)}-$date.zip")
                withContext(Dispatchers.IO) {
                    cardPhotos.zip(FileSystem.SYSTEM.sink(path.toPath()).buffer(), "${fileSafe(scope)}-$date.${format.extension}",
                        Char(0xFEFF) + content, photoNames)
                }
                app.files.sharePath(path, "application/zip", subject, title)
            }
            format == CollectionExport.Format.TEXT -> app.files.shareText(content, subject, title)
            else -> {
                val suffix = if (format == CollectionExport.Format.MOXFIELD) "-moxfield" else ""
                // BOM para que Excel abra bien las tildes.
                app.files.shareBytes("${fileSafe(scope)}-$date$suffix.${format.extension}", format.mime,
                    (Char(0xFEFF) + content).encodeToByteArray(), subject, title)
            }
        }
    }

    private fun deckName(id: Long?) = id?.let { d -> _ui.value.decks.firstOrNull { it.id == d }?.name }

    // ------------------------------------------------------------------ copia de seguridad e importar

    // Fotos de prueba (solo en Android; ver TestPhotos).
    val hasTestPhotos get() = app.testPhotos != null
    val testPhotosEnabled: StateFlow<Boolean> = settings.testPhotos

    fun setTestPhotos(on: Boolean) = settings.setTestPhotos(on)

    /** Cuántas fotos hay y cuánto ocupan. */
    suspend fun testPhotosInfo(): Pair<Int, Long> =
        withContext(Dispatchers.IO) { app.testPhotos?.let { it.count() to it.size() } ?: (0 to 0L) }

    /** Comparte el zip con las fotos y su respuesta correcta (la carta tal como está ahora). */
    suspend fun shareTestPhotos() {
        app.testPhotos?.share(getString(Res.string.test_photos_share))
    }

    suspend fun clearTestPhotos() = withContext(Dispatchers.IO) { app.testPhotos?.clear() }

    // Fotos de las cartas (ver CardPhotos).
    val hasCardPhotos get() = app.cardPhotos != null
    val cardPhotosEnabled: StateFlow<Boolean> = settings.cardPhotos

    fun setCardPhotos(on: Boolean) = settings.setCardPhotos(on)

    /** Cuántas fotos hay y cuánto ocupan. */
    suspend fun cardPhotosInfo(): Pair<Int, Long> =
        withContext(Dispatchers.IO) { app.cardPhotos?.let { it.count() to it.size() } ?: (0 to 0L) }

    suspend fun clearCardPhotos() = withContext(Dispatchers.IO) { app.cardPhotos?.clear() }

    // Copia automática (ver AutoBackup).
    val hasAutoBackup get() = app.autoBackup != null
    val autoBackup: StateFlow<AutoBackupState> = app.autoBackup?.state ?: MutableStateFlow(AutoBackupState())

    fun enableAutoBackup(uri: String) = runCatching { app.autoBackup?.enable(uri) }
        .onFailure { e -> viewModelScope.launch { showMessage(getString(Res.string.backup_failed, e.message.orEmpty())) } }

    fun disableAutoBackup() = app.autoBackup?.disable()

    fun enableAutoBackupPhotos(uri: String) = runCatching { app.autoBackup?.enablePhotos(uri) }
        .onFailure { e -> viewModelScope.launch { showMessage(getString(Res.string.backup_failed, e.message.orEmpty())) } }

    fun disableAutoBackupPhotos() = app.autoBackup?.disablePhotos()

    fun autoBackupNow() {
        val backup = app.autoBackup ?: return
        viewModelScope.launch {
            val msg = runCatching { backup.runNow() }.fold(
                onSuccess = { cards -> cards?.let { getString(Res.string.backup_saved, cardsText(it)) } },
                onFailure = { getString(Res.string.backup_failed, it.message.orEmpty()) },
            )
            msg?.let(::showMessage)
        }
    }

    /** "mtg-scanner-copia-2026-10-05.csv" ([prefix]: el comienzo traducido). */
    fun backupFileName(prefix: String) = "$prefix-${localDay(nowMillis())}.csv"

    /** Copia de seguridad (CollectionBackup) en el sitio que elija el usuario (Drive, Descargas...). */
    fun writeBackup(uri: String) {
        viewModelScope.launch {
            val msg = runCatching {
                val content = CollectionBackup.build(app.catalog.await(), store, settings, app.englishExport)
                app.files.write(uri, content.csv.encodeToByteArray())
                content.cards
            }.fold(
                onSuccess = { getString(Res.string.backup_saved, cardsText(it)) },
                onFailure = { getString(Res.string.backup_failed, it.message.orEmpty()) },
            )
            showMessage(msg)
        }
    }

    /** Lee un fichero y busca sus cartas en el catálogo, sin guardar nada todavía. */
    suspend fun readImport(uri: String): ImportPreview = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val bytes = runCatching { app.files.read(uri, MAX_IMPORT_BYTES) }
            .getOrElse { throw IllegalArgumentException(getString(Res.string.file_too_big), it) }
        val rows = CollectionImport.parse(CollectionImport.decode(bytes))
        val result = CollectionImport.resolve(rows, catalog.importLookup, settings.defaultCondition.value)
        val ui = _ui.value
        ImportPreview(
            fileName = app.files.displayName(uri) ?: getString(Res.string.file_default_name),
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
                onSuccess = { getString(if (replace) Res.string.collection_replaced else Res.string.cards_added, cardsText(it)) },
                onFailure = { getString(Res.string.import_failed, it.message.orEmpty()) },
            )
            if (replace) filter.value = CollectionFilter()
            showMessage(msg)
            // Los precios del catálogo son los de cuando se hizo la app.
            if (preview.result.items.isNotEmpty()) updatePrices(quiet = true)
        }
    }

    /** "Mazo Goblins · Rojo" -> "mazo-goblins-rojo" (nombre de fichero). */
    private fun fileSafe(s: String) = nfkd(s.lowercase())
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
