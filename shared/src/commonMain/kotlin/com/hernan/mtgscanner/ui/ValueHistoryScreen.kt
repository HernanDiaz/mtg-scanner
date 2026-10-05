package com.hernan.mtgscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import com.hernan.mtgscanner.data.formatDay
import com.hernan.mtgscanner.data.formatNumber
import kotlin.math.abs

private val UP = Color(0xFF4CAF50)
private val DOWN = Color(0xFFE57373)

/** Evolución del valor de la colección y las cartas que más han subido o bajado. */
@Composable
fun ValueHistoryDialog(
    currency: Currency,
    load: suspend (HistoryRange) -> ValueHistory,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf(HistoryRange.MONTH) }
    var data by remember { mutableStateOf<ValueHistory?>(null) }
    LaunchedEffect(range) { data = null; data = load(range) }
    // Dentro del diálogo no llegan los márgenes de las barras del sistema: se miden fuera.
    val bottomBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(bottom = bottomBar).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.value_history), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(Res.string.close)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryRange.entries.forEach { r ->
                        FilterChip(selected = r == range, onClick = { range = r }, label = { Text(stringResource(r.label)) })
                    }
                }
                val d = data
                when {
                    d == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                    d.points.size < 2 -> Text(stringResource(Res.string.history_empty), modifier = Modifier.padding(vertical = 16.dp))
                    else -> ValueChart(d.points, currency)
                }
                if (d != null) {
                    Text(stringResource(Res.string.history_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Movers(stringResource(Res.string.biggest_rises), d.up, currency)
                    Movers(stringResource(Res.string.biggest_drops), d.down, currency)
                    if (d.up.isEmpty() && d.down.isEmpty()) {
                        Text(stringResource(Res.string.no_price_changes), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

/** Gráfica de línea del valor; al tocarla o arrastrar, muestra el valor de ese día. */
@Composable
private fun ValueChart(points: List<CollectionStore.ValuePoint>, currency: Currency) {
    val first = points.first()
    val last = points.last()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val shown = points[selected ?: points.lastIndex]
    val change = shown.value - first.value

    Column(Modifier.padding(top = 12.dp)) {
        Text(formatPrice(shown.value, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "${signed(change, currency)} (${percent(change, first.value)}) · ${longDate(shown.day)} · ${cardsLabel(shown.cards)}",
            color = if (change >= 0) UP else DOWN, style = MaterialTheme.typography.bodyMedium,
        )
        val line = if (last.value >= first.value) UP else DOWN
        val grid = MaterialTheme.colorScheme.outlineVariant
        val days = points.map { epochDay(it.day) }
        val minX = days.first().toFloat()
        val spanX = (days.last() - days.first()).coerceAtLeast(1).toFloat()
        val minY = points.minOf { it.value }
        val maxY = points.maxOf { it.value }
        val pad = ((maxY - minY) * 0.1).coerceAtLeast(maxOf(maxY * 0.01, 0.01))
        val lo = minY - pad
        val hi = maxY + pad

        fun nearest(x: Float, width: Float): Int {
            val day = minX + x / width * spanX
            return days.indices.minBy { abs(days[it] - day) }
        }

        Canvas(
            Modifier.fillMaxWidth().height(220.dp).padding(vertical = 12.dp)
                .pointerInput(points) { detectTapGestures { selected = nearest(it.x, size.width.toFloat()) } }
                .pointerInput(points) {
                    detectDragGestures(onDragEnd = {}) { change, _ -> selected = nearest(change.position.x, size.width.toFloat()) }
                },
        ) {
            fun at(i: Int) = Offset(
                (days[i] - minX) / spanX * size.width,
                (size.height - (points[i].value - lo) / (hi - lo) * size.height).toFloat(),
            )
            // Líneas guía: arriba, medio y abajo.
            for (f in listOf(0f, 0.5f, 1f)) {
                drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = 1f)
            }
            val path = Path().apply { points.indices.forEach { i -> at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
            val area = Path().apply {
                addPath(path)
                lineTo(at(points.lastIndex).x, size.height)
                lineTo(at(0).x, size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
            selected?.let { i ->
                val p = at(i)
                drawLine(grid, Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 2f)
                drawCircle(line, radius = 6.dp.toPx(), center = p)
            }
        }
        Row {
            Text(shortDate(first.day), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("${formatPrice(minY, currency)} – ${formatPrice(maxY, currency)}", style = MaterialTheme.typography.labelSmall)
            Text(shortDate(last.day), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun Movers(title: String, movers: List<CollectionStore.Mover>, currency: Currency) {
    if (movers.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    movers.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CardThumb(m.smallImageUrl, 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(m.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${m.setCode.uppercase()}${if (m.foil) " · Foil" else ""} · " +
                        "${formatPrice(m.before, currency)} → ${formatPrice(m.now, currency)}" +
                        if (m.quantity > 1) " · x${m.quantity}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(signed(m.change * m.quantity, currency), color = if (m.change >= 0) UP else DOWN, fontWeight = FontWeight.Bold)
                Text(percent(m.change, m.before), style = MaterialTheme.typography.bodySmall,
                    color = if (m.change >= 0) UP else DOWN)
            }
        }
        HorizontalDivider()
    }
}

private fun signed(v: Double, currency: Currency) = (if (v >= 0) "+" else "−") + formatPrice(abs(v), currency)

private fun percent(change: Double, base: Double): String =
    if (base <= 0) "—" else (change / base * 100).let { (if (it >= 0) "+" else "") + formatNumber(it, 1) + " %" }


/** Días desde el 1-1-1970 de un día "yyyy-MM-dd" (calendario gregoriano, sin zona horaria). */
internal fun epochDay(day: String): Int {
    val (y0, m, d) = day.split('-').map(String::toInt)
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun longDate(day: String) = formatDay(day, long = true)

private fun shortDate(day: String) = formatDay(day, long = false)
