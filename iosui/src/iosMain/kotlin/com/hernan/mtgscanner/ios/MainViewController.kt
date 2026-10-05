package com.hernan.mtgscanner.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.data.openCatalog
import com.hernan.mtgscanner.scan.FrameRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import kotlin.time.TimeSource

/** Pantalla principal de la app; la crea el SceneDelegate de Swift. */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController { RecognitionTestScreen() }

/** El catálogo y el reconocedor, como en la app Android (ScannerViewModel). */
private class Engine(val catalog: Catalog) {
    val recognizer = FrameRecognizer(
        catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber, catalog::art, catalog.symbols,
        catalog::nameLanguages, catalog::setLanguages, catalog::searchArt,
    )
}

private class Recognition(val photo: ImageBitmap?, val summary: String, val details: String)

/**
 * Primera versión de iPhone (paso 2a): comprueba en el móvil el catálogo y el
 * reconocimiento con fotos de la fototeca. El escáner en directo llega en el paso 2d.
 */
@Composable
private fun RecognitionTestScreen() {
    var engine by remember { mutableStateOf<Engine?>(null) }
    var status by remember { mutableStateOf("Cargando el catálogo…") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Recognition?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.Default) {
            runCatching {
                val path = NSBundle.mainBundle.pathForResource("catalog", ofType = "sqlite")
                    ?: error("El catálogo no está en la app")
                val start = TimeSource.Monotonic.markNow()
                val catalog = openCatalog(path)
                engine = Engine(catalog)
                "Catálogo cargado en ${start.elapsedNow().inWholeMilliseconds} ms: ${catalog.setCodes.size} ediciones."
            }.getOrElse { "No se ha podido cargar el catálogo: ${it.message}" }
        }
    }

    fun recognize(image: UIImage) {
        val e = engine ?: return
        busy = true
        scope.launch {
            result = withContext(Dispatchers.Default) { runCatching { recognizePhoto(e, image) }.getOrElse {
                Recognition(null, "Error: ${it.message}", "")
            } }
            busy = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("MTG Scanner", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Versión de prueba para iPhone: elige la foto de una carta y la app te dice cuál es.",
                    style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { pickPhoto { image -> image?.let(::recognize) } },
                    enabled = engine != null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Elegir foto de una carta") }
                if (busy) CircularProgressIndicator()
                result?.let { r ->
                    r.photo?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
                    }
                    Text(r.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(r.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** OCR con Vision y el mismo reconocimiento que en Android, con tiempos y detalles. */
private fun recognizePhoto(engine: Engine, image: UIImage): Recognition {
    val start = TimeSource.Monotonic.markNow()
    val prepared = PreparedImage.from(image)
    val lines = recognizeText(prepared)
    val ocrMs = start.elapsedNow().inWholeMilliseconds
    val r = engine.recognizer.recognize(lines, prepared)
    val totalMs = start.elapsedNow().inWholeMilliseconds
    val photo = image.jpegBytes()?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
    val summary = if (r == null) {
        "No la reconozco"
    } else {
        val p = r.printing
        "${p.name} · ${p.setName} (${p.setCode.uppercase()} ${p.collectorNumber}) · ${r.lang.uppercase()}" +
            if (r.exact) " · segura" else " · para revisar"
    }
    val details = buildString {
        appendLine("Tiempo: $totalMs ms (OCR $ocrMs ms) · foto ${prepared.width}×${prepared.height}")
        engine.recognizer.artDebug?.let { appendLine("Ilustración: $it") }
        engine.recognizer.borderDebug?.let { appendLine("Borde: ${it.take(120)}") }
        appendLine()
        appendLine("Texto leído:")
        lines.sortedBy { it.top }.forEach { appendLine(it.text) }
    }
    return Recognition(photo, summary, details)
}
