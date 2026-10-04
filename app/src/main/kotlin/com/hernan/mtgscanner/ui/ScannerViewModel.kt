package com.hernan.mtgscanner.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hernan.mtgscanner.App
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.TestPhotos
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.FrameResult
import com.hernan.mtgscanner.scan.ScanSession
import com.hernan.mtgscanner.scan.TextAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val store = app.collection
    val settings = app.settings

    private val _ui = MutableStateFlow(ScannerUi())
    val ui: StateFlow<ScannerUi> = _ui

    private val _analyzer = MutableStateFlow<TextAnalyzer?>(null)
    val analyzer: StateFlow<TextAnalyzer?> = _analyzer

    /** Un solo hilo: el OCR y [session] nunca se ejecutan en paralelo. */
    val analysisExecutor = Executors.newSingleThreadExecutor()
    private val session = ScanSession()

    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
    private val vibrator = application.getSystemService(Vibrator::class.java)

    // Fotos de prueba. Antes de init: sus corrutinas pueden usarlas antes de que
    // termine el constructor (y una propiedad declarada después aún sería null).
    private val testPhotos = app.testPhotos
    /** Último fotograma en que se leyó cada nombre (solo con el modo de prueba). */
    private val recentFrames = LinkedHashMap<String, Bitmap>()
    @Volatile private var captureNext = false
    private var photoSeq = 0

    init {
        viewModelScope.launch {
            val catalog = app.catalog.await()
            val recognizer = FrameRecognizer(catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber, catalog::art, catalog.symbols,
                catalog::nameLanguages, catalog::setLanguages, catalog::searchArt)
            _analyzer.value = TextAnalyzer(recognizer, analysisExecutor, ::onFrame)
            _ui.update { it.copy(loading = false) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            settings.testPhotos.collect { on -> if (on) _ui.update { it.copy(testPhotoCount = testPhotos.count()) } }
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

    private fun savePhoto(bitmap: Bitmap, entryId: Long?, got: FrameResult?) {
        val now = System.currentTimeMillis()
        val (w, h) = _analyzer.value?.viewSize ?: (bitmap.width to bitmap.height)
        testPhotos.save(bitmap, TestPhotos.Photo(
            TestPhotos.fileName(now, photoSeq++), entryId, got?.name, got?.printing?.setCode, got?.printing?.collectorNumber,
            got?.exact == true, got?.lang, w, h, now,
        ))
        _ui.update { it.copy(testPhotoCount = it.testPhotoCount + 1) }
    }

    /** Llamado desde [analysisExecutor] con cada fotograma procesado (y desde las pruebas, sin cámara). */
    @VisibleForTesting
    internal fun onFrame(result: FrameResult?, rawTopLine: String?, frame: () -> Bitmap) {
        val reading = result?.let { "${it.name} · ${it.printing.setCode.uppercase()} ${it.printing.collectorNumber}" }
            ?: rawTopLine
        if (reading != _ui.value.reading) _ui.update { it.copy(reading = reading) }

        val testing = settings.testPhotos.value
        val keepPhoto = settings.cardPhotos.value
        if ((testing || keepPhoto) && result != null) {
            recentFrames.remove(result.name)
            recentFrames[result.name] = frame()
            while (recentFrames.size > 3) recentFrames.remove(recentFrames.keys.first())
        }
        if (captureNext) {
            captureNext = false
            val bmp = frame()
            viewModelScope.launch(Dispatchers.IO) { savePhoto(bmp, null, result) }
        }

        val accepted = session.onFrame(result, SystemClock.elapsedRealtime()) ?: return
        val photo = if (testing || keepPhoto) recentFrames.remove(accepted.name) else null
        val printing = accepted.printing
        val foil = (_ui.value.foil && printing.hasFoil) || !printing.hasNonfoil
        val needsReview = !accepted.exact
        feedback(needsReview)
        viewModelScope.launch(Dispatchers.IO) {
            val id = store.add(printing, foil, settings.defaultCondition.value, needsReview, accepted.lang,
                accepted.photoArt?.toPacked(), deckId = _ui.value.scanDeck?.id)
            if (keepPhoto) photo?.let { runCatching { app.cardPhotos.save(id, it) } }
            if (testing) photo?.let { savePhoto(it, id, accepted) }
            val wished = store.fulfillWish(printing.name)
            _ui.update { it.copy(lastAdded = AddedCard(id, printing, foil, needsReview, accepted.lang, wished)) }
        }
    }

    fun undo() {
        val last = _ui.value.lastAdded ?: return
        _ui.update { it.copy(lastAdded = null) }
        analysisExecutor.execute { session.unlock() }
        viewModelScope.launch(Dispatchers.IO) {
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

    private fun feedback(needsReview: Boolean) {
        // Dos pitidos si la edición es dudosa, uno si está identificada.
        tone?.startTone(if (needsReview) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120)
        vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onCleared() {
        _analyzer.value?.close()
        analysisExecutor.shutdown()
        tone?.release()
    }
}
