package com.hernan.mtgscanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.App
import com.hernan.mtgscanner.data.CardPrinting
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

data class CollectionFilters(
    val query: String = "",
    val onlyPending: Boolean = false,
    val sort: CollectionStore.Sort = CollectionStore.Sort.RECENT,
)

data class CollectionUi(
    val groups: List<CollectionStore.Group> = emptyList(),
    val summary: CollectionStore.Summary = CollectionStore.Summary(0, 0.0, 0),
    val currency: Currency = Currency.EUR,
    val filters: CollectionFilters = CollectionFilters(),
    val updatingPrices: Boolean = false,
    val message: String? = null,
)

/** Una edición posible en el selector, con su parecido a la foto (0-1) si se conoce. */
data class PrintingOption(val printing: CardPrinting, val similarity: Double?)

class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val store = app.collection
    private val settings = app.settings
    private val updater = PriceUpdater(store)

    private val filters = MutableStateFlow(CollectionFilters())
    private val _ui = MutableStateFlow(CollectionUi())
    val ui: StateFlow<CollectionUi> = _ui

    init {
        viewModelScope.launch {
            combine(store.version, settings.currency, filters) { _, c, f -> c to f }.collect { (currency, f) ->
                val (groups, summary) = withContext(Dispatchers.IO) {
                    store.groups(currency, f.query, f.onlyPending, f.sort) to store.summary(currency)
                }
                _ui.update { it.copy(groups = groups, summary = summary, currency = currency, filters = f) }
            }
        }
        // Precios del día: si los más antiguos tienen más de 24 h, se actualizan solos.
        viewModelScope.launch {
            val oldest = withContext(Dispatchers.IO) { store.oldestPriceUpdate() }
            if (oldest != null && System.currentTimeMillis() - oldest > DAY_MS) updatePrices()
        }
    }

    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setOnlyPending(v: Boolean) = filters.update { it.copy(onlyPending = v) }
    fun setSort(s: CollectionStore.Sort) = filters.update { it.copy(sort = s) }

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

    /** Primera fila pendiente de revisar (para el modo revisión), o null si no queda ninguna. */
    suspend fun firstPending(): CollectionStore.Group? = withContext(Dispatchers.IO) {
        store.groups(_ui.value.currency, onlyPending = true).firstOrNull()
    }

    /** Asigna la edición (o confirma la sugerida) y devuelve la siguiente pendiente. */
    suspend fun reassignAndNext(group: CollectionStore.Group, printing: CardPrinting): CollectionStore.Group? =
        withContext(Dispatchers.IO) {
            store.reassign(group, printing)
            store.groups(_ui.value.currency, onlyPending = true).firstOrNull()
        }

    suspend fun confirmAndNext(group: CollectionStore.Group): CollectionStore.Group? =
        withContext(Dispatchers.IO) {
            store.confirm(group)
            store.groups(_ui.value.currency, onlyPending = true).firstOrNull()
        }

    fun addManual(printing: CardPrinting, foil: Boolean, lang: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.add(printing, foil && printing.hasFoil || !printing.hasNonfoil,
                settings.defaultCondition.value, needsReview = false, lang = lang)
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

    suspend fun searchNames(query: String): List<String> =
        withContext(Dispatchers.Default) { app.catalog.await().names.search(query) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
