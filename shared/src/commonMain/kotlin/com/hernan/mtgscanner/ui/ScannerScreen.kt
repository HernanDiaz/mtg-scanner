package com.hernan.mtgscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.GuideRegion

@Composable
fun ScannerScreen(vm: ScannerViewModel = LocalServices.current.let { s -> viewModel { ScannerViewModel(s) } }) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recognizer by vm.recognizer.collectAsStateWithLifecycle()
    val currency by vm.settings.currency.collectAsStateWithLifecycle()
    val testing by vm.settings.testPhotos.collectAsStateWithLifecycle()
    var choosingDeck by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<com.hernan.mtgscanner.data.CardPrinting?>(null) }

    // El OCR solo mira dentro del marco guía; necesita saber cómo se ve la cámara.
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { vm.viewSize = it.width to it.height }) {
        recognizer?.let { CameraView(vm, it, ui.torch) }
        CardGuide()

        // Arriba: total de la colección y lo que está leyendo el OCR.
        Column(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cardsLabel(ui.summary.cards), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(formatPrice(ui.summary.total, currency), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
            }
            Text(
                if (ui.loading) stringResource(Res.string.loading_catalog) else ui.reading ?: stringResource(Res.string.point_at_card),
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))

        // Abajo: última carta añadida, deshacer y opciones.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            ui.lastAdded?.let { added -> LastAddedCard(added, currency, onUndo = vm::undo, onOpen = { detail = added.printing }) }
            Spacer(Modifier.size(8.dp))
            // Modo de prueba: cuántas fotos van y guardar la de una carta que no reconoce.
            if (testing) {
                // Cuánto tarda cada fotograma (medida técnica, sin traducir).
                ui.timing?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        pluralStringResource(Res.plurals.test_photos_count, ui.testPhotoCount, ui.testPhotoCount),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = vm::captureFrame) { Text(stringResource(Res.string.test_photos_capture)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ui.foil, onClick = vm::toggleFoil, label = { Text(stringResource(Res.string.foil)) })
                FilterChip(selected = ui.torch, onClick = vm::toggleTorch, label = { Text(stringResource(Res.string.torch)) })
                FilterChip(
                    selected = ui.scanDeck != null, onClick = { choosingDeck = true },
                    label = {
                        Text(ui.scanDeck?.let { stringResource(Res.string.deck_named, it.name) } ?: stringResource(Res.string.no_deck),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }

    detail?.let { p ->
        CardDetailDialog(
            printing = p, group = null, deckName = null, currency = currency,
            loadSheet = { vm.cardSheet(p) }, loadDetails = { vm.cardDetails(p.id) },
            onDismiss = { detail = null },
        )
    }
    if (choosingDeck) {
        ScanDeckDialog(
            decks = ui.decks, current = ui.scanDeck?.id,
            onPick = { vm.setScanDeck(it); choosingDeck = false },
            onCreate = { vm.createScanDeck(it); choosingDeck = false },
            onDismiss = { choosingDeck = false },
        )
    }
}

/** Elegir el mazo al que irán las cartas que se escaneen. */
@Composable
private fun ScanDeckDialog(
    decks: List<com.hernan.mtgscanner.data.CollectionStore.Deck>, current: Long?,
    onPick: (Long?) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.scan_deck_question)) },
        text = {
            Column {
                Text((if (current == null) "✓ " else "") + stringResource(Res.string.no_deck),
                    Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp))
                decks.forEach { d ->
                    Text((if (d.id == current) "✓ " else "") + stringResource(Res.string.deck_with_count, d.name, d.cards),
                        Modifier.fillMaxWidth().clickable { onPick(d.id) }.padding(vertical = 12.dp))
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.or_new_deck)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(newName) }, enabled = newName.isNotBlank()) { Text(stringResource(Res.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun LastAddedCard(added: AddedCard, currency: com.hernan.mtgscanner.data.Currency, onUndo: () -> Unit, onOpen: () -> Unit) {
    val p = added.printing
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CardThumb(p.smallImageUrl, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.setName} · #${p.collectorNumber} · ${added.lang.uppercase()}${if (added.foil) " · Foil" else ""}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (added.wished) {
                    Text(stringResource(Res.string.was_on_wishlist), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (added.needsReview) {
                    Text(stringResource(Res.string.doubtful_edition), style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB74D))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPrice(p.prices.of(currency, added.foil), currency), fontWeight = FontWeight.Bold)
                TextButton(onClick = onUndo) { Text(stringResource(Res.string.undo)) }
            }
        }
    }
}

/** Marco con proporción de carta (63×88 mm): solo se lee el texto de dentro. */
@Composable
private fun CardGuide() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * GuideRegion.WIDTH_FRACTION
        val h = w * GuideRegion.CARD_ASPECT
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f), topLeft = topLeft, size = GeoSize(w, h),
            cornerRadius = CornerRadius(w * 0.05f), style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * La cámara a pantalla completa, con su OCR: cada fotograma se reconoce con [recognizer]
 * y se pasa a [ScannerViewModel.onFrame]. Pide el permiso de la cámara si hace falta
 * (mientras, muestra [CameraPermissionNeeded]). Android: CameraX + ML Kit; iPhone:
 * AVFoundation + Vision.
 */
@Composable
expect fun CameraView(vm: ScannerViewModel, recognizer: FrameRecognizer, torch: Boolean)

/** Sin permiso para la cámara: explicación y botón para pedirlo. */
@Composable
fun CameraPermissionNeeded(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.camera_needed))
        Spacer(Modifier.size(12.dp))
        Button(onClick = onRequest) { Text(stringResource(Res.string.grant_permission)) }
    }
}

/**
 * Zoom de la cámara al escanear. Sin zoom, para llenar el marco guía la carta queda a
 * ~8 cm: más cerca de lo que enfoca la mayoría de móviles, y la línea de edición y número
 * sale borrosa. Con zoom se sostiene a ~15 cm, enfocada, y sin perder detalle (el sensor
 * tiene mucha más resolución que la imagen que se analiza). No más de ~2x: algunos móviles
 * pasan ahí al teleobjetivo, que no enfoca de cerca.
 */
const val SCAN_ZOOM = 1.7f
