package com.hernan.mtgscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionImport
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.DeckCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WishlistUi(
    val items: List<CollectionStore.Wish> = emptyList(),
    val currency: Currency = Currency.EUR,
    val loaded: Boolean = false,
) {
    val cards get() = items.sumOf { it.quantity }
    val total get() = items.sumOf { (it.unitPrice ?: 0.0) * it.quantity }
}

/** Lista de deseos y "¿qué me falta para este mazo?". */
class WishlistViewModel(private val app: AppServices) : ViewModel() {
    private val store = app.collection
    private val settings = app.settings

    private val _ui = MutableStateFlow(WishlistUi())
    val ui: StateFlow<WishlistUi> = _ui

    init {
        viewModelScope.launch {
            combine(store.version, settings.currency, settings.priceKind) { _, c, k -> c to k }.collect { (currency, kind) ->
                val items = withContext(Dispatchers.IO) { store.wishlist(currency, kind) }
                _ui.update { it.copy(items = items, currency = currency, loaded = true) }
            }
        }
    }

    fun setQuantity(name: String, quantity: Int) {
        viewModelScope.launch(Dispatchers.IO) { store.setWishQuantity(name, quantity) }
    }

    /** Compara una lista de mazo (texto de Moxfield, Arena, MTGO, CSV...) con la colección. */
    suspend fun checkDeck(text: String): DeckCheck.Result = withContext(Dispatchers.IO) {
        val catalog = app.catalog.await()
        val resolved = CollectionImport.resolve(CollectionImport.parse(text), catalog.importLookup)
        DeckCheck.check(resolved, store.ownedByName())
    }

    suspend fun readFile(uri: String): String = withContext(Dispatchers.IO) {
        CollectionImport.decode(app.files.read(uri, MAX_DECK_BYTES))
    }

    /** Lo que falta, a la lista de deseos (sin duplicar si ya estaba). */
    fun addMissing(result: DeckCheck.Result) {
        viewModelScope.launch(Dispatchers.IO) {
            for (line in result.missing) store.addWish(line.printing, line.missing)
        }
    }

    /** Precio de tendencia de una copia normal (del catálogo): para estimar lo que cuesta lo que falta. */
    fun priceOf(p: CardPrinting): Double? = p.prices.of(_ui.value.currency, foil = false)

    // Ficha de carta
    suspend fun printingOf(id: String): CardPrinting? = withContext(Dispatchers.IO) { app.catalog.await().byId(id) }

    suspend fun cardSheet(id: String, name: String): CardSheet =
        withContext(Dispatchers.IO) { CardSheet(store.storedPrices(id), store.copiesOf(name)) }

    suspend fun cardDetails(id: String): CardDetails? =
        withContext(Dispatchers.IO) { runCatching { CardDetails.fetch(id) }.getOrNull() }
}

/** Una lista de mazo no pasa de unos KB; más es otro tipo de fichero. */
private const val MAX_DECK_BYTES = 5 * 1024 * 1024
