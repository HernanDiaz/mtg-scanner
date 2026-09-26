package com.hernan.mtgscanner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionExport
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer

private val REVIEW_COLOR = Color(0xFFFFB74D)

/** Idiomas que se pueden asignar a una carta de la colección. */
private val LANGUAGES = listOf("en" to "Inglés", "es" to "Español")

private val SORTS = listOf(
    CollectionStore.Sort.RECENT to "Recientes",
    CollectionStore.Sort.VALUE to "Valor",
    CollectionStore.Sort.NAME to "Nombre",
)

@Composable
fun CollectionScreen(vm: CollectionViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    /** Fila abierta en el selector de edición; [reviewing]: al terminar, pasar a la siguiente pendiente. */
    var editing by remember { mutableStateOf<CollectionStore.Group?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<CollectionStore.Group?>(null) }
    /** Diálogo de nombre de mazo: crear (id null) o renombrar. */
    var deckDialog by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    var deletingDeck by remember { mutableStateOf<CollectionStore.Deck?>(null) }
    var fileMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<ImportPreview?>(null) }
    var readingFile by remember { mutableStateOf(false) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::writeBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            readingFile = true
            runCatching { vm.readImport(uri) }
                .onSuccess { importing = it }
                .onFailure { vm.showMessage("No se pudo leer el fichero: ${it.message}") }
            readingFile = false
        }
    }
    val context = LocalContext.current
    val currentDeck = ui.decks.firstOrNull { it.id == ui.filter.deckId }

    fun openNext(next: CollectionStore.Group?) {
        editing = if (reviewing) next else null
        if (next == null) reviewing = false
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatPrice(ui.summary.total, ui.currency),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${ui.scopeLabel()} · ${cardsLabel(ui.summary.cards)}", style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { exporting = true }, enabled = ui.summary.cards > 0) {
                        Icon(Icons.Default.Share, "Exportar y compartir lo que se ve")
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Add, "Añadir a mano") }
                    if (ui.updatingPrices || readingFile) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
                    } else {
                        IconButton(onClick = { vm.updatePrices() }) { Icon(Icons.Default.Refresh, "Actualizar precios") }
                    }
                    Box {
                        IconButton(onClick = { fileMenu = true }) { Icon(Icons.Default.MoreVert, "Copia de seguridad e importar") }
                        DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Guardar copia de seguridad") },
                                onClick = { fileMenu = false; backupLauncher.launch(vm.backupFileName()) },
                            )
                            DropdownMenuItem(
                                text = { Text("Importar cartas o copia…") },
                                onClick = { fileMenu = false; importLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                }
                if (ui.pendingTotal > 0) {
                    Button(
                        onClick = {
                            scope.launch {
                                reviewing = true
                                openNext(vm.firstPending())
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("Revisar ediciones (${ui.pendingTotal} pendientes)") }
                }

                // Mazos
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = ui.filter.deckId == null, onClick = { vm.showDeck(null) }, label = { Text("Todas") })
                    ui.decks.forEach { d ->
                        FilterChip(selected = ui.filter.deckId == d.id, onClick = { vm.showDeck(d.id) },
                            label = { Text("${d.name} (${d.cards})") })
                    }
                    if (ui.decks.isNotEmpty()) {
                        FilterChip(selected = ui.filter.deckId == CollectionStore.NO_DECK,
                            onClick = { vm.showDeck(CollectionStore.NO_DECK) }, label = { Text("Sin mazo") })
                    }
                    FilterChip(selected = false, onClick = { deckDialog = null to "" },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }, label = { Text("Mazo") })
                }
                currentDeck?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { deckDialog = d.id to d.name }) { Text("Renombrar mazo") }
                        TextButton(onClick = { deletingDeck = d }) { Text("Borrar mazo") }
                    }
                }

                // Colores
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    COLOR_NAMES.keys.forEach { c ->
                        ColorToggle(c, selected = c in ui.filter.colors, onClick = { vm.toggleColor(c) })
                    }
                }

                OutlinedTextField(
                    value = ui.filter.query, onValueChange = vm::setQuery, singleLine = true,
                    placeholder = { Text("Buscar en la colección") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (ui.filter.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Borrar") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = ui.filter.onlyPending, onClick = { vm.setOnlyPending(!ui.filter.onlyPending) },
                        label = { Text("Por revisar") },
                    )
                    SORTS.forEach { (sort, label) ->
                        FilterChip(selected = ui.sort == sort, onClick = { vm.setSort(sort) }, label = { Text(label) })
                    }
                    Currency.entries.forEach { c ->
                        FilterChip(selected = ui.currency == c, onClick = { vm.setCurrency(c) }, label = { Text(c.symbol) })
                    }
                }
                ui.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    LaunchedEffect(it) { delay(4000); vm.clearMessage() }
                }
            }
        }

        if (ui.groups.isEmpty()) {
            val onlyDeck = ui.filter.query.isEmpty() && !ui.filter.onlyPending && ui.filter.colors.isEmpty()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        currentDeck != null && onlyDeck ->
                            "Mazo vacío. Mueve cartas aquí desde el menú de cada carta, o escanea con este mazo como destino."
                        ui.filter.deckId == null && onlyDeck -> "Aún no hay cartas. Ve a Escanear."
                        else -> "Ninguna carta coincide."
                    },
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.groups, key = { "${it.cardId}-${it.foil}-${it.condition}-${it.lang}-${it.needsReview}-${it.deckId}" }) { g ->
                GroupRow(
                    g, ui.currency,
                    deckName = if (ui.filter.deckId == null) ui.decks.firstOrNull { it.id == g.deckId }?.name else null,
                    onClick = { reviewing = false; editing = g },
                    onMove = { moving = g },
                    onRemove = { vm.removeOne(g) },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { group ->
        var lang by remember(group) { mutableStateOf(group.lang) }
        PrintingPicker(
            title = group.name,
            subtitle = if (reviewing) "Revisando: quedan ${ui.pendingTotal}" else null,
            load = { vm.optionsFor(group.name, group.art) },
            currentId = group.cardId,
            currency = ui.currency,
            foil = group.foil,
            lang = lang,
            onLangChange = { vm.setLanguage(group.copy(lang = lang), it); lang = it },
            onConfirm = if (group.needsReview) {
                { scope.launch { openNext(vm.confirmAndNext(group.copy(lang = lang))) } }
            } else null,
            onPick = { p -> scope.launch { openNext(vm.reassignAndNext(group.copy(lang = lang), p)) } },
            onDismiss = { editing = null; reviewing = false },
        )
    }
    moving?.let { group ->
        MoveToDeckDialog(
            group = group, decks = ui.decks,
            onMove = { deckId, count -> vm.moveToDeck(group, deckId, count); moving = null },
            onCreateAndMove = { name, count ->
                scope.launch { vm.moveToDeck(group, vm.createDeckNow(name), count) }
                moving = null
            },
            onDismiss = { moving = null },
        )
    }
    deckDialog?.let { (id, initial) ->
        DeckNameDialog(
            title = if (id == null) "Nuevo mazo" else "Renombrar mazo", initial = initial,
            onConfirm = { name -> if (id == null) vm.createDeck(name) else vm.renameDeck(id, name); deckDialog = null },
            onDismiss = { deckDialog = null },
        )
    }
    deletingDeck?.let { d ->
        AlertDialog(
            onDismissRequest = { deletingDeck = null },
            title = { Text("¿Borrar el mazo ${d.name}?") },
            text = { Text("Sus cartas (${cardsLabel(d.cards)}) no se borran: vuelven a la colección, sin mazo.") },
            confirmButton = { TextButton(onClick = { vm.deleteDeck(d.id); deletingDeck = null }) { Text("Borrar mazo") } },
            dismissButton = { TextButton(onClick = { deletingDeck = null }) { Text("Cancelar") } },
        )
    }
    if (searching) {
        ManualAddDialog(vm, ui.currency, onDismiss = { searching = false })
    }
    importing?.let { preview ->
        ImportDialog(
            preview = preview,
            onImport = { replace -> vm.applyImport(preview, replace); importing = null },
            onDismiss = { importing = null },
        )
    }
    if (exporting) {
        ExportDialog(
            scope = ui.scopeLabel(),
            cards = ui.summary.cards,
            onPick = { format ->
                exporting = false
                scope.launch { context.startActivity(vm.exportIntent(format)) }
            },
            onDismiss = { exporting = false },
        )
    }
}

/** Botón redondo de color (W U B R G y C = incoloro), como los símbolos de maná. */
@Composable
private fun ColorToggle(c: Char, selected: Boolean, onClick: () -> Unit) {
    val (fill, ink) = when (c) {
        'W' -> Color(0xFFF8F1D8) to Color.Black
        'U' -> Color(0xFF2E7FD1) to Color.White
        'B' -> Color(0xFF3A3238) to Color.White
        'R' -> Color(0xFFD9443A) to Color.White
        'G' -> Color(0xFF2F8F4E) to Color.White
        else -> Color(0xFFB9B4AE) to Color.Black
    }
    Box(
        Modifier.size(36.dp)
            .clip(CircleShape)
            .background(if (selected) fill else fill.copy(alpha = 0.25f))
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${COLOR_NAMES[c]}${if (selected) ", seleccionado" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(c.toString(), color = if (selected) ink else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

/**
 * Resultado de leer un fichero y qué hacer con él: añadirlo a la colección o
 * sustituirla (esto último pide una segunda confirmación, porque borra).
 */
@Composable
private fun ImportDialog(preview: ImportPreview, onImport: (replace: Boolean) -> Unit, onDismiss: () -> Unit) {
    val r = preview.result
    var confirmReplace by remember { mutableStateOf(false) }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("¿Sustituir la colección?") },
            text = {
                Text("Se borrarán las ${cardsLabel(preview.currentCards)} actuales" +
                    (if (preview.currentDecks > 0) " y ${decksLabel(preview.currentDecks)}" else "") +
                    ", y quedarán solo las ${cardsLabel(r.cards)} del fichero. No se puede deshacer.")
            },
            confirmButton = { TextButton(onClick = { onImport(true) }) { Text("Sustituir") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancelar") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar ${preview.fileName}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (r.items.isEmpty()) {
                    Text("No se ha reconocido ninguna carta. Se admiten CSV (de esta app, Moxfield, ManaBox, " +
                        "Deckbox, TCGplayer...) y listas de texto como \"4 Lightning Bolt (M11) 149\".")
                } else {
                    Text("${cardsLabel(r.cards)} reconocidas", fontWeight = FontWeight.Bold)
                    if (r.decks.isNotEmpty()) Text("En ${decksLabel(r.decks.size)}: ${r.decks.joinToString(", ")}")
                    if (r.pending > 0) Text("${cardsLabel(r.pending)} con la edición por revisar", color = REVIEW_COLOR)
                    if (preview.targetDeck != null) {
                        Text("Al añadir, las que no traen mazo irán a ${preview.targetDeck.name}.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (r.failed.isNotEmpty()) {
                    Text("${r.failed.size} ${if (r.failed.size == 1) "línea no reconocida" else "líneas no reconocidas"}:",
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    r.failed.take(MAX_FAILED_SHOWN).forEach {
                        Text("Línea ${it.line}: ${it.text}", style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (r.failed.size > MAX_FAILED_SHOWN) {
                        Text("… y ${r.failed.size - MAX_FAILED_SHOWN} más", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (r.items.isNotEmpty()) {
                    if (preview.currentCards > 0) TextButton(onClick = { confirmReplace = true }) { Text("Sustituir") }
                    TextButton(onClick = { onImport(false) }) { Text(if (preview.currentCards > 0) "Añadir" else "Importar") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private const val MAX_FAILED_SHOWN = 20

private fun decksLabel(n: Int) = if (n == 1) "1 mazo" else "$n mazos"

/** Elegir formato de exportación de lo que se está viendo; luego se abre el menú de compartir de Android. */
@Composable
private fun ExportDialog(scope: String, cards: Int, onPick: (CollectionExport.Format) -> Unit, onDismiss: () -> Unit) {
    val descriptions = mapOf(
        CollectionExport.Format.CSV to "Todo, con precios. Para Excel, Google Sheets u otras apps (incluye el ID de Scryfall).",
        CollectionExport.Format.MOXFIELD to "Para importar las cartas en Moxfield.",
        CollectionExport.Format.TEXT to "\"2 Lightning Bolt (M11) 149\": para pegar en un mensaje o un foro.",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar: $scope (${cardsLabel(cards)})") },
        text = {
            Column {
                CollectionExport.Format.entries.forEach { f ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 10.dp)) {
                        Text(f.label, fontWeight = FontWeight.Bold)
                        Text(descriptions.getValue(f), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun GroupRow(
    g: CollectionStore.Group, currency: Currency, deckName: String?,
    onClick: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(g.smallImageUrl, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (g.needsReview) {
                    Icon(Icons.Default.Warning, "Por revisar", tint = REVIEW_COLOR, modifier = Modifier.size(16.dp))
                }
                Text(g.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${g.setCode.uppercase()} #${g.collectorNumber} · ${g.condition} · ${g.lang.uppercase()}${if (g.foil) " · Foil" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
            deckName?.let {
                Text("Mazo: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("x${g.quantity}", style = MaterialTheme.typography.bodySmall)
            Text(formatPrice(g.unitPrice?.times(g.quantity), currency), fontWeight = FontWeight.Bold)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Mover a un mazo…") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text("Cambiar edición o idioma") }, onClick = { menu = false; onClick() })
                DropdownMenuItem(text = { Text("Quitar una copia") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

/** Mover copias de una fila a un mazo (o sacarlas de su mazo). */
@Composable
private fun MoveToDeckDialog(
    group: CollectionStore.Group,
    decks: List<CollectionStore.Deck>,
    onMove: (deckId: Long?, count: Int) -> Unit,
    onCreateAndMove: (name: String, count: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableStateOf(group.quantity) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover ${group.name}") },
        text = {
            Column {
                if (group.quantity > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Copias:", Modifier.weight(1f))
                        TextButton(onClick = { if (count > 1) count-- }) { Text("−") }
                        Text("$count de ${group.quantity}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { if (count < group.quantity) count++ }) { Text("+") }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    if (group.deckId != null) {
                        item {
                            Text("Sacar del mazo (sin mazo)", Modifier.fillMaxWidth().clickable { onMove(null, count) }
                                .padding(vertical = 12.dp))
                            HorizontalDivider()
                        }
                    }
                    items(decks.filter { it.id != group.deckId }, key = { it.id }) { d ->
                        Text("${d.name} (${d.cards})", Modifier.fillMaxWidth().clickable { onMove(d.id, count) }
                            .padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text("O a un mazo nuevo") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreateAndMove(newName, count) }, enabled = newName.isNotBlank()) { Text("Crear y mover") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun DeckNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Nombre del mazo") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Selector de edición a pantalla completa. Arriba, la edición actual (sugerida)
 * con un botón para darla por buena; debajo, un buscador y la lista, ordenada por
 * parecido con la ilustración fotografiada si se conoce.
 */
@Composable
private fun PrintingPicker(
    title: String,
    subtitle: String?,
    load: suspend () -> List<PrintingOption>,
    currentId: String?,
    currency: Currency,
    foil: Boolean,
    lang: String,
    onLangChange: (String) -> Unit,
    onConfirm: (() -> Unit)?,
    onPick: (CardPrinting) -> Unit,
    onDismiss: () -> Unit,
) {
    var options by remember(title, currentId) { mutableStateOf<List<PrintingOption>?>(null) }
    var query by remember(title, currentId) { mutableStateOf("") }
    LaunchedEffect(title, currentId) { options = load() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = REVIEW_COLOR) }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar") }
                }

                val list = options
                val current = list?.firstOrNull { it.printing.id == currentId }?.printing
                if (current != null) {
                    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CardThumb(current.smallImageUrl, 64.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(if (onConfirm != null) "Edición sugerida" else "Edición actual",
                                    style = MaterialTheme.typography.labelMedium)
                                Text(current.setName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${current.setCode.uppercase()} #${current.collectorNumber} · ${current.releasedAt?.take(4) ?: ""} · " +
                                    formatPrice(current.prices.of(currency, foil), currency), style = MaterialTheme.typography.bodySmall)
                            }
                            if (onConfirm != null) {
                                Button(onClick = onConfirm) {
                                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("Es correcta")
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Idioma", style = MaterialTheme.typography.bodySmall)
                    LANGUAGES.forEach { (code, label) ->
                        FilterChip(selected = lang == code, onClick = { onLangChange(code) }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Buscar edición: nombre, código, número o año") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filtered = list.filter { matchesPrinting(it.printing, query) }
                    val byPhoto = list.any { it.similarity != null }
                    Text(
                        "${filtered.size} de ${list.size} ediciones · " +
                            if (byPhoto) "ordenadas por parecido con tu foto" else "de la más reciente a la más antigua",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.printing.id }) { opt ->
                            PrintingRow(opt, opt.printing.id == currentId, currency, foil, onClick = { onPick(opt.printing) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrintingRow(opt: PrintingOption, isCurrent: Boolean, currency: Currency, foil: Boolean, onClick: () -> Unit) {
    val p = opt.printing
    Row(
        Modifier.fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(p.smallImageUrl, 56.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(p.setName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${p.setCode.uppercase()} #${p.collectorNumber} · ${p.releasedAt?.take(4) ?: ""}",
                style = MaterialTheme.typography.bodySmall)
            opt.similarity?.let {
                Text("Parecido con tu foto: ${(it.coerceAtLeast(0.0) * 100).toInt()} %",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it >= 0.6) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(formatPrice(p.prices.of(currency, foil), currency))
    }
}

/** El texto de búsqueda casa con nombre de la edición, código, número o año (sin tildes ni mayúsculas). */
private fun matchesPrinting(p: CardPrinting, query: String): Boolean {
    val q = fold(query.trim())
    if (q.isEmpty()) return true
    return q.split(Regex("\\s+")).all { word ->
        fold(p.setName).contains(word) || p.setCode.startsWith(word) ||
            p.collectorNumber.lowercase().startsWith(word) || (p.releasedAt?.startsWith(word) == true)
    }
}

private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
    .filter { it.code !in 0x300..0x36F }
    .lowercase()

/** Añadir una carta buscándola por nombre (para las que el escáner no lee). */
@Composable
private fun ManualAddDialog(vm: CollectionViewModel, currency: Currency, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<String>()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var lang by remember { mutableStateOf("en") }
    LaunchedEffect(query) {
        delay(150)
        results = if (query.length >= 2) vm.searchNames(query) else emptyList()
    }

    val name = chosen
    if (name != null) {
        PrintingPicker(
            title = name, subtitle = "Elige la edición para añadirla",
            load = { vm.optionsFor(name, null) }, currentId = null, currency = currency, foil = false,
            lang = lang, onLangChange = { lang = it }, onConfirm = null,
            onPick = { vm.addManual(it, foil = false, lang = lang); chosen = null },
            onDismiss = { chosen = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir carta") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Nombre (inglés o español)") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results) { n ->
                        Text(n, Modifier.fillMaxWidth().clickable { chosen = n }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
