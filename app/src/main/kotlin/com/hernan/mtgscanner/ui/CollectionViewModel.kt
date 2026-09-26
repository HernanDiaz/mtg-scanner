package com.hernan.mtgscanner.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.App
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionImport
import com.hernan.mtgscanner.data.CollectionFilter
import com.hernan.mtgscanner.data.CollectionStore
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
    val groups: List<CollectionStore.Group> = emptyList(),
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
    val message: String? = null,
) {
    /** "Toda la colección", "Mazo Goblins · Rojo"... para títulos y exportaciones. */
    fun scopeLabel(): String {
        val deck = when (filter.deckId) {
            null -> null
            CollectionStore.NO_DECK -> "Sin mazo"
            else -> decks.firstOrNull { it.id == filter.deckId }?.let { "Mazo ${it.name}" }
        }
        val colors = filter.colors.takeIf { it.isNotEmpty() }?.let { set -> COLOR_NAMES.filterKeys { it in set }.values.joinToString(" o ") }
        return listOfNotNull(deck, colors, filter.query.takeIf { it.isNotBlank() }?.let { "“$it”" })
            .joinToString(" · ").ifEmpty { "Toda la colección" }
    }
}

/** Nombres de los colores del filtro, en el orden habitual de Magic. */
val COLOR_NAMES = linkedMapOf('W' to "Blanco", 'U' to "Azul", 'B' to "Negro", 'R' to "Rojo", 'G' to "Verde", 'C' to "Incoloro")

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
                        )
                    }
                    _ui.update {
                        it.copy(groups = result.groups, summary = result.summary, pendingTotal = result.pendingTotal,
                            decks = result.decks, currency = currency, filter = f, sort = s, priceKind = kind)
                    }
                }
        }
        // Las cartas escaneadas con versiones anteriores no guardaban sus colores.
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = app.catalog.await()
            store.backfillColors { id -> catalog.byId(id)?.colors }
            store.backfillCardmarketIds { id -> catalog.byId(id)?.cardmarketId }
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
        val groups: List<CollectionStore.Group>,
        val summary: CollectionStore.Summary,
        val pendingTotal: Int,
        val decks: List<CollectionStore.Deck>,
    )

    // ------------------------------------------------------------------ filtros

    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun setOnlyPending(v: Boolean) = filter.update { it.copy(onlyPending = v) }
    fun setSort(s: CollectionStore.Sort) { sort.value = s }
    /** null = toda la colección. */
    fun showDeck(deckId: Long?) = filter.update { it.copy(deckId = deckId) }
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

    fun moveToDeck(group: CollectionStore.Group, deckId: Long?, count: Int) {
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
                onSuccess = { if (quiet) null else "Precios actualizados ($it cartas)" },
                onFailure = { "No se pudieron actualizar los precios: ${it.message}" },
            )
            _ui.update { it.copy(updatingPrices = false, message = msg ?: it.message) }
        }
    }

    fun setCurrency(c: Currency) = settings.setCurrency(c)

    /** Al elegir el precio más bajo o la media por primera vez hace falta descargar la guía. */
    fun setPriceKind(k: PriceKind) {
        if (k == settings.priceKind.value) return
        settings.setPriceKind(k)
        if (k != PriceKind.TREND && _ui.value.summary.cards + _ui.value.pendingTotal > 0) updatePrices()
    }

    fun removeOne(group: CollectionStore.Group) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(group.lastEntryId) }
    }

    private fun pendingFilter() = CollectionFilter(onlyPending = true)

    /** Primera fila pendiente de revisar (para el modo revisión), o null si no queda ninguna. */
    suspend fun firstPending(): CollectionStore.Group? = withContext(Dispatchers.IO) {
        store.groups(_ui.value.currency, pendingFilter()).firstOrNull()
    }

    /** Asigna la edición (o confirma la sugerida) y devuelve la siguiente pendiente. */
    suspend fun reassignAndNext(group: CollectionStore.Group, printing: CardPrinting): CollectionStore.Group? =
        withContext(Dispatchers.IO) {
            store.reassign(group, printing)
            store.groups(_ui.value.currency, pendingFilter()).firstOrNull()
        }

    suspend fun confirmAndNext(group: CollectionStore.Group): CollectionStore.Group? =
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
        }
    }

    fun setLanguage(group: CollectionStore.Group, lang: String) {
        viewModelScope.launch(Dispatchers.IO) { store.setLanguage(group, lang) }
    }

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
    suspend fun exportIntent(format: CollectionExport.Format): Intent = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val ui = _ui.value
        val lines = CollectionExport.lines(store.groups(ui.currency, ui.filter, kind = ui.priceKind), catalog::setName, ::deckName,
            detailed = format == CollectionExport.Format.CSV)
        val content = CollectionExport.render(format, lines, ui.currency)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val scope = ui.scopeLabel()
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, "Cartas MTG: $scope ($date)")
        if (format == CollectionExport.Format.TEXT) {
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
        Intent.createChooser(send, "Compartir: $scope")
    }

    private fun deckName(id: Long?) = id?.let { d -> _ui.value.decks.firstOrNull { it.id == d }?.name }

    // ------------------------------------------------------------------ copia de seguridad e importar

    fun backupFileName() = "mtg-scanner-copia-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.csv"

    /**
     * Copia de seguridad: nuestro CSV con toda la colección (sin filtros), con mazos
     * y cartas por revisar, en el sitio que elija el usuario (Drive, Descargas...).
     */
    fun writeBackup(uri: Uri) {
        viewModelScope.launch {
            val msg = runCatching {
                withContext(Dispatchers.IO) {
                    val catalog = app.catalog.await()
                    val currency = _ui.value.currency
                    val lines = CollectionExport.lines(store.groups(currency, kind = _ui.value.priceKind), catalog::setName,
                        ::deckName, detailed = true)
                    val content = Char(0xFEFF) + CollectionExport.csv(lines, currency)
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    lines.sumOf { it.quantity }
                }
            }.fold(
                onSuccess = { "Copia de seguridad guardada (${cardsLabel(it)})" },
                onFailure = { "No se pudo guardar la copia: ${it.message}" },
            )
            _ui.update { it.copy(message = msg) }
        }
    }

    /** Lee un fichero y busca sus cartas en el catálogo, sin guardar nada todavía. */
    suspend fun readImport(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val bytes = app.contentResolver.openInputStream(uri)!!.use { input ->
            val data = input.readBytes()
            require(data.size <= MAX_IMPORT_BYTES) { "el fichero es demasiado grande" }
            data
        }
        val rows = CollectionImport.parse(decode(bytes))
        val result = CollectionImport.resolve(rows, object : CollectionImport.Lookup {
            override fun byId(id: String) = catalog.byId(id)
            override fun bySetAndNumber(setCode: String, numberNorm: String) = catalog.bySetAndNumber(setCode, numberNorm)
            override fun printingsOf(name: String) = catalog.printingsOfAnyName(name)
            override fun setCode(value: String) = catalog.setCodeFor(value)
            override fun isForeignName(name: String) = catalog.isForeignName(name)
        }, settings.defaultCondition.value)
        val ui = _ui.value
        ImportPreview(
            fileName = displayName(uri) ?: "fichero",
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
                onSuccess = { "${if (replace) "Colección sustituida" else "Añadidas"}: ${cardsLabel(it)}" },
                onFailure = { "No se pudo importar: ${it.message}" },
            )
            if (replace) filter.value = CollectionFilter()
            _ui.update { it.copy(message = msg) }
            // Los precios del catálogo son los de cuando se hizo la app.
            if (preview.result.items.isNotEmpty()) updatePrices(quiet = true)
        }
    }

    /** UTF-8 si lo es (nuestros ficheros, la mayoría de apps); si no, lo que guarda Excel en Windows. */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        String(bytes, charset("windows-1252"))
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

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
