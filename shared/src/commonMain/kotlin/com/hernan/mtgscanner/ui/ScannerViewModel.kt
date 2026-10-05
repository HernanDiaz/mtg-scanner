package com.hernan.mtgscanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.DbLock
import com.hernan.mtgscanner.data.TestPhotos
import com.hernan.mtgscanner.data.nowMillis
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.FrameResult
import com.hernan.mtgscanner.scan.ScanFrame
import com.hernan.mtgscanner.scan.ScanSession
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AddedCard(
    val entryId: Long,
    val printing: CardPrinting,
    val foil: Boolean,
    val needsReview: Boolean,
    val lang: String,
    /** Estaba en la lista de deseos (y se ha restado de ella). */
    val wished: Boolean = false,
)

data class ScannerUi(
    val loading: Boolean = true,
    val foil: Boolean = false,
    val torch: Boolean = false,
    val lastAdded: AddedCard? = null,
    /** Lo último que ha leído el OCR, para saber qué está viendo. */
    val reading: String? = null,
    val summary: CollectionStore.Summary = CollectionStore.Summary(0, 0.0, 0),
    /** Mazo al que van las cartas escaneadas (null = ninguno). */
    val scanDeck: CollectionStore.Deck? = null,
    val decks: List<CollectionStore.Deck> = emptyList(),
    /** Fotos de prueba guardadas (con el modo de prueba activado). */
    val testPhotoCount: Int = 0,
)

/**
 * El escáner: recibe cada fotograma ya reconocido (la cámara y el OCR son de cada
 * plataforma, ver CameraView), decide con [ScanSession] cuándo una carta está leída y la
 * añade a la colección (con su foto, si se guardan).
 */
class ScannerViewModel(private val app: AppServices) : ViewModel() {
    private val store = app.collection
    val settings = app.settings

    private val _ui = MutableStateFlow(ScannerUi())
    val ui: StateFlow<ScannerUi> = _ui

    private val _recognizer = MutableStateFlow<FrameRecognizer?>(null)
    /** Listo cuando carga el catálogo; la cámara lo usa con cada fotograma. */
    val recognizer: StateFlow<FrameRecognizer?> = _recognizer

    /** Tamaño en píxeles de la vista de la cámara, para situar el marco guía (lo pone CameraView). */
    @Volatile var viewSize: Pair<Int, Int>? = null

    /** [session] no es para varios hilos: los fotogramas y "deshacer" pasan de uno en uno. */
    private val sessionLock = DbLock()
    private val session = ScanSession()
    private val clock = TimeSource.Monotonic.markNow()

    private val testPhotos = app.testPhotos
    /** Último fotograma en que se leyó cada nombre (con fotos de cartas o de prueba). */
    private val recentFrames = LinkedHashMap<String, ScanFrame>()
    @Volatile private var captureNext = false
    private var photoSeq = 0

    init {
        viewModelScope.launch {
            val catalog = app.catalog.await()
            _recognizer.value = FrameRecognizer(catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber,
                catalog::art, catalog.symbols, catalog::nameLanguages, catalog::setLanguages, catalog::searchArt)
            _ui.update { it.copy(loading = false) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            settings.testPhotos.collect { on -> if (on) _ui.update { it.copy(testPhotoCount = testPhotos?.count() ?: 0) } }
        }
        viewModelScope.launch {
            combine(store.version, settings.currency, settings.scanDeck, settings.priceKind) { _, currency, deck, kind ->
                Triple(currency, deck, kind)
            }
                .collect { (currency, deckId, kind) ->
                    val (summary, decks) = withContext(Dispatchers.IO) {
                        store.summary(currency, kind = kind) to store.decks()
                    }
                    val deck = decks.firstOrNull { it.id == deckId }
                    if (deckId != null && deck == null) settings.setScanDeck(null) // el mazo se borró
                    _ui.update { it.copy(summary = summary, decks = decks, scanDeck = deck) }
                }
        }
    }

    /** Ficha de carta de la última escaneada. */
    suspend fun cardSheet(p: CardPrinting): CardSheet =
        withContext(Dispatchers.IO) { CardSheet(store.storedPrices(p.id), store.copiesOf(p.name)) }

    suspend fun cardDetails(id: String): CardDetails? =
        withContext(Dispatchers.IO) { runCatching { CardDetails.fetch(id) }.getOrNull() }

    // ------------------------------------------------------------------ fotos de prueba

    /** Guarda el próximo fotograma aunque no se reconozca la carta. */
    fun captureFrame() { captureNext = true }

    private fun savePhoto(frame: ScanFrame, entryId: Long?, got: FrameResult?) {
        val photos = testPhotos ?: return
        val now = nowMillis()
        val (w, h) = viewSize ?: (frame.width to frame.height)
        photos.save(frame, TestPhotos.Photo(
            TestPhotos.fileName(now, photoSeq++), entryId, got?.name, got?.printing?.setCode, got?.printing?.collectorNumber,
            got?.exact == true, got?.lang, w, h, now,
        ))
        _ui.update { it.copy(testPhotoCount = it.testPhotoCount + 1) }
    }

    /**
     * Cada fotograma procesado por la cámara (y por las pruebas, sin cámara). [frame] da el
     * fotograma derecho; solo vale durante la llamada.
     */
    fun onFrame(result: FrameResult?, rawTopLine: String?, frame: () -> ScanFrame) = sessionLock.withLock {
        val reading = result?.let { "${it.name} · ${it.printing.setCode.uppercase()} ${it.printing.collectorNumber}" }
            ?: rawTopLine
        if (reading != _ui.value.reading) _ui.update { it.copy(reading = reading) }

        val testing = settings.testPhotos.value && testPhotos != null
        val keepPhoto = settings.cardPhotos.value && app.cardPhotos != null
        if ((testing || keepPhoto) && result != null) {
            recentFrames.remove(result.name)
            recentFrames[result.name] = frame()
            while (recentFrames.size > 3) recentFrames.remove(recentFrames.keys.first())
        }
        if (captureNext) {
            captureNext = false
            val f = frame()
            viewModelScope.launch(Dispatchers.IO) { savePhoto(f, null, result) }
        }

        val accepted = session.onFrame(result, clock.elapsedNow().inWholeMilliseconds) ?: return@withLock
        val photo = if (testing || keepPhoto) recentFrames.remove(accepted.name) else null
        val printing = accepted.printing
        val foil = (_ui.value.foil && printing.hasFoil) || !printing.hasNonfoil
        val needsReview = !accepted.exact
        scanFeedback(needsReview)
        viewModelScope.launch(Dispatchers.IO) {
            val id = store.add(printing, foil, settings.defaultCondition.value, needsReview, accepted.lang,
                accepted.photoArt?.toPacked(), deckId = _ui.value.scanDeck?.id)
            if (keepPhoto) photo?.let { runCatching { app.cardPhotos?.save(id, it) } }
            if (testing) photo?.let { savePhoto(it, id, accepted) }
            val wished = store.fulfillWish(printing.name)
            _ui.update { it.copy(lastAdded = AddedCard(id, printing, foil, needsReview, accepted.lang, wished)) }
        }
    }

    fun undo() {
        val last = _ui.value.lastAdded ?: return
        _ui.update { it.copy(lastAdded = null) }
        viewModelScope.launch(Dispatchers.IO) {
            sessionLock.withLock { session.unlock() }
            store.delete(last.entryId)
            if (last.wished) store.unfulfillWish(last.printing)
        }
    }

    fun toggleFoil() = _ui.update { it.copy(foil = !it.foil) }

    /** Mazo de destino de lo que se escanea (null = ninguno). */
    fun setScanDeck(id: Long?) = settings.setScanDeck(id)

    fun createScanDeck(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { settings.setScanDeck(store.createDeck(name)) }
    }

    fun toggleTorch() = _ui.update { it.copy(torch = !it.torch) }
}

/** Dos pitidos y una vibración corta si la edición es dudosa; uno si está identificada. */
expect fun scanFeedback(needsReview: Boolean)
