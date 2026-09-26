package com.hernan.mtgscanner.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.App
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionFilter
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
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

/** Una edición posible en el selector, con su parecido a la foto (0-1) si se conoce. */
data class PrintingOption(val printing: CardPrinting, val similarity: Double?)

class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val store = app.collection
    private val settings = app.settings
    private val updater = PriceUpdater(store)

    private val filter = MutableStateFlow(CollectionFilter())
    private val sort = MutableStateFlow(CollectionStore.Sort.RECENT)
    private val _ui = MutableStateFlow(CollectionUi())
    val ui: StateFlow<CollectionUi> = _ui

    init {
        viewModelScope.launch {
            combine(store.version, settings.currency, filter, sort) { _, c, f, s -> Triple(c, f, s) }
                .collect { (currency, f, s) ->
                    val result = withContext(Dispatchers.IO) {
                        Loaded(
                            groups = store.groups(currency, f, s),
                            summary = store.summary(currency, f),
                            pendingTotal = store.summary(currency).pendingReview,
                            decks = store.decks(),
                        )
                    }
                    _ui.update {
                        it.copy(groups = result.groups, summary = result.summary, pendingTotal = result.pendingTotal,
                            decks = result.decks, currency = currency, filter = f, sort = s)
                    }
                }
        }
        // Las cartas escaneadas con versiones anteriores no guardaban sus colores.
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = app.catalog.await()
            store.backfillColors { id -> catalog.byId(id)?.colors }
        }
        // Precios del día: si los más antiguos tienen más de 24 h, se actualizan solos.
        viewModelScope.launch {
            val oldest = withContext(Dispatchers.IO) { store.oldestPriceUpdate() }
            if (oldest != null && System.currentTimeMillis() - oldest > DAY_MS) updatePrices()
        }
    }

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

    fun updatePrices() {
        if (_ui.value.updatingPrices) return
        _ui.update { it.copy(updatingPrices = true, message = null) }
        viewModelScope.launch {
            val msg = runCatching { updater.updateAll() }.fold(
                onSuccess = { "Precios actualizados ($it cartas)" },
                onFailure = { "No se pudieron actualizar los precios: ${it.message}" },
            )
            _ui.update { it.copy(updatingPrices = false, message = msg) }
        }
    }

    fun setCurrency(c: Currency) = settings.setCurrency(c)

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
        val lines = CollectionExport.lines(store.groups(ui.currency, ui.filter), catalog::setName)
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

    /** "Mazo Goblins · Rojo" -> "mazo-goblins-rojo" (nombre de fichero). */
    private fun fileSafe(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "coleccion" }.take(60)

    suspend fun searchNames(query: String): List<String> =
        withContext(Dispatchers.Default) { app.catalog.await().names.search(query) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
