package com.hernan.mtgscanner.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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

    fun openNext(next: CollectionStore.Group?) {
        editing = if (reviewing) next else null
        if (next == null) reviewing = false
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatPrice(ui.summary.total, ui.currency),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${ui.summary.cards} cartas", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Add, "Añadir a mano") }
                    if (ui.updatingPrices) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
                    } else {
                        IconButton(onClick = vm::updatePrices) { Icon(Icons.Default.Refresh, "Actualizar precios") }
                    }
                }
                if (ui.summary.pendingReview > 0) {
                    Button(
                        onClick = {
                            scope.launch {
                                reviewing = true
                                openNext(vm.firstPending())
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Revisar ediciones (${ui.summary.pendingReview} pendientes)") }
                }
                OutlinedTextField(
                    value = ui.filters.query, onValueChange = vm::setQuery, singleLine = true,
                    placeholder = { Text("Buscar en la colección") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (ui.filters.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Borrar") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = ui.filters.onlyPending, onClick = { vm.setOnlyPending(!ui.filters.onlyPending) },
                        label = { Text("Por revisar") },
                    )
                    SORTS.forEach { (sort, label) ->
                        FilterChip(selected = ui.filters.sort == sort, onClick = { vm.setSort(sort) }, label = { Text(label) })
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (ui.summary.cards == 0) "Aún no hay cartas. Ve a Escanear." else "Ninguna carta coincide.")
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.groups, key = { "${it.cardId}-${it.foil}-${it.condition}-${it.lang}-${it.needsReview}" }) { g ->
                GroupRow(g, ui.currency, onClick = { reviewing = false; editing = g }, onRemove = { vm.removeOne(g) })
                HorizontalDivider()
            }
        }
    }

    editing?.let { group ->
        var lang by remember(group) { mutableStateOf(group.lang) }
        PrintingPicker(
            title = group.name,
            subtitle = if (reviewing) "Revisando: quedan ${ui.summary.pendingReview}" else null,
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
    if (searching) {
        ManualAddDialog(vm, ui.currency, onDismiss = { searching = false })
    }
}

@Composable
private fun GroupRow(g: CollectionStore.Group, currency: Currency, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
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
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("x${g.quantity}", style = MaterialTheme.typography.bodySmall)
            Text(formatPrice(g.unitPrice?.times(g.quantity), currency), fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Quitar una") }
    }
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
