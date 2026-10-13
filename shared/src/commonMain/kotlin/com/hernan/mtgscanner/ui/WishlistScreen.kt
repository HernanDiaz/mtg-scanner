package com.hernan.mtgscanner.ui

import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.DeckCheck
import kotlinx.coroutines.launch

private val MISSING_COLOR = Color(0xFFFFB74D)

@Composable
fun WishlistScreen(vm: WishlistViewModel = LocalServices.current.let { s -> viewModel { WishlistViewModel(s) } }) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<CollectionStore.Wish?>(null) }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(formatPrice(ui.total, ui.currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.wishlist_summary, cardsLabel(ui.cards)), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { checking = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(Res.string.check_deck))
                }
            }
        }
        if (ui.loaded && ui.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.wishlist_empty), modifier = Modifier.padding(24.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.items, key = { it.name }) { w ->
                WishRow(w, ui.currency, onClick = { viewing = w }, onQuantity = { vm.setQuantity(w.name, it) })
                HorizontalDivider()
            }
        }
    }

    viewing?.let { w ->
        var printing by remember(w.cardId) { mutableStateOf<CardPrinting?>(null) }
        LaunchedEffect(w.cardId) { printing = vm.printingOf(w.cardId) }
        printing?.let { p ->
            CardDetailDialog(
                printing = p, group = null, deckName = null, currency = ui.currency,
                loadSheet = { vm.cardSheet(p.id, p.name) }, loadDetails = { vm.cardDetails(p.id) },
                onDismiss = { viewing = null },
            )
        }
    }
    if (checking) DeckCheckDialog(vm, ui.currency, onDismiss = { checking = false })
}

@Composable
private fun WishRow(w: CollectionStore.Wish, currency: Currency, onClick: () -> Unit, onQuantity: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(w.smallImageUrl, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(w.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${w.setCode.uppercase()} #${w.collectorNumber} · ${formatPrice(w.unitPrice, currency)}",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onQuantity(w.quantity - 1) }) { Text("−") }
        Text("${w.quantity}", fontWeight = FontWeight.Bold)
        TextButton(onClick = { onQuantity(w.quantity + 1) }) { Text("+") }
    }
}

/**
 * "¿Qué me falta para este mazo?": se pega la lista (o se abre un fichero) y se
 * compara con la colección.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckCheckDialog(vm: WishlistViewModel, currency: Currency, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<DeckCheck.Result?>(null) }
    var working by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val openFile = rememberOpenDocument { uri ->
        scope.launch { runCatching { vm.readFile(uri) }.onSuccess { text = it } }
    }

    // Dentro del diálogo no llegan los márgenes de las barras del sistema: se miden fuera. El
    // diálogo empieza bajo la barra de estado pero mide toda la pantalla: sobra también ese alto.
    val bottomBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(bottom = bottomBar).imePadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.check_deck), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(Res.string.close)) }
                }
                val r = result
                if (r == null) {
                    Text(stringResource(Res.string.check_deck_help), style = MaterialTheme.typography.bodySmall)
                    TextInput(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("4 Lightning Bolt\n4 Counterspell (EMA) 43\n1 Sol Ring") },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { openFile() }) { Text(stringResource(Res.string.open_file)) }
                        Button(
                            onClick = { scope.launch { working = true; result = vm.checkDeck(text); working = false } },
                            enabled = text.isNotBlank() && !working,
                        ) { Text(stringResource(Res.string.check)) }
                        if (working) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else {
                    DeckCheckResult(r, currency, vm::priceOf, Modifier.weight(1f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.missing.isNotEmpty()) {
                            Button(onClick = { vm.addMissing(r); added = true }, enabled = !added) {
                                Text(stringResource(if (added) Res.string.added_missing else Res.string.add_missing_to_wishlist))
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(r.missingAsText())); copied = true }) {
                                Text(stringResource(if (copied) Res.string.copied else Res.string.copy_missing))
                            }
                        }
                        TextButton(onClick = { result = null; added = false; copied = false }) { Text(stringResource(Res.string.another_list)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckCheckResult(r: DeckCheck.Result, currency: Currency, priceOf: (CardPrinting) -> Double?, modifier: Modifier) {
    Column(modifier) {
        Text(stringResource(Res.string.deck_have, r.ownedCards, r.wantedCards), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        if (r.missingCards > 0) {
            Text(stringResource(Res.string.deck_missing, cardsLabel(r.missingCards), formatPrice(r.missingCost(priceOf), currency)),
                color = MISSING_COLOR)
        } else {
            Text(stringResource(Res.string.deck_complete), color = MaterialTheme.colorScheme.primary)
        }
        val legal = r.legalFormats()
        Text(
            if (legal.isEmpty()) stringResource(Res.string.deck_legal_none)
            else stringResource(Res.string.deck_legal_in, legal.joinToString(", ") { com.hernan.mtgscanner.data.Legality.displayName(it) }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        )
        if (r.failed.isNotEmpty()) {
            Text(pluralStringResource(Res.plurals.lines_not_recognised, r.failed.size, r.failed.size) + " " +
                r.failed.take(5).joinToString(", ") { it.text }, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(vertical = 8.dp)) {
            items(r.lines, key = { it.name }) { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CardThumb(l.printing.smallImageUrl, 36.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(l.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(Res.string.have_n_of_m, l.owned, l.wanted), style = MaterialTheme.typography.bodySmall,
                            color = if (l.missing > 0) MISSING_COLOR else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (l.missing > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("−${l.missing}", color = MISSING_COLOR, fontWeight = FontWeight.Bold)
                            Text(formatPrice(priceOf(l.printing)?.times(l.missing), currency), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
