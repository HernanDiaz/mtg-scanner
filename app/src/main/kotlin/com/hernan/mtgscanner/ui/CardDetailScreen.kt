package com.hernan.mtgscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.hernan.mtgscanner.R
import com.hernan.mtgscanner.data.CONDITIONS
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency
import java.text.DateFormat
import java.util.Date

/** Lo que la ficha lee de la colección: precios guardados y copias de la carta. */
class CardSheet(val prices: CollectionStore.StoredPrices?, val copiesOfCard: Int)

/**
 * Ficha de una carta: imagen grande, precios, texto, legalidad y enlaces. Si se
 * abre desde una fila de la colección ([group]), también sus copias y acciones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardDetailDialog(
    printing: CardPrinting,
    group: CollectionStore.Group?,
    deckName: String?,
    currency: Currency,
    loadSheet: suspend () -> CardSheet,
    loadDetails: suspend () -> CardDetails?,
    onCondition: (String) -> Unit = {},
    onFoil: (Boolean) -> Unit = {},
    onChangeEdition: () -> Unit = {},
    onMove: () -> Unit = {},
    onRemoveOne: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var sheet by remember(printing.id, group) { mutableStateOf<CardSheet?>(null) }
    var details by remember(printing.id) { mutableStateOf<CardDetails?>(null) }
    var detailsState by remember(printing.id) { mutableStateOf(DetailsState.LOADING) }
    LaunchedEffect(printing.id, group) { sheet = loadSheet() }
    LaunchedEffect(printing.id) {
        details = loadDetails()
        detailsState = if (details == null) DetailsState.OFFLINE else DetailsState.READY
    }
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(printing.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                Text(
                    listOfNotNull(printing.setName, "${printing.setCode.uppercase()} #${printing.collectorNumber}",
                        printing.releasedAt?.take(4), printing.rarity?.let { rarityLabel(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )

                CardImage(printing, twoFaced = (details?.faces?.size ?: 0) > 1)

                // En tu colección
                if (group != null) {
                    Section(stringResource(R.string.in_your_collection))
                    val parts = listOfNotNull(
                        stringResource(R.string.copies_of_edition, group.quantity),
                        sheet?.let { stringResource(R.string.copies_of_card, it.copiesOfCard) },
                        stringResource(if (group.lang == "es") R.string.lang_es else R.string.lang_en),
                        deckName?.let { stringResource(R.string.deck_named, it) },
                    )
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = group.foil, onClick = { onFoil(!group.foil) }, label = { Text(stringResource(R.string.foil)) })
                        CONDITIONS.forEach { c ->
                            FilterChip(selected = group.condition == c, onClick = { onCondition(c) }, label = { Text(c) })
                        }
                    }
                    FlowRow {
                        TextButton(onClick = onChangeEdition) { Text(stringResource(R.string.change_edition)) }
                        TextButton(onClick = onMove) { Text(stringResource(R.string.move_to_deck)) }
                        TextButton(onClick = onRemoveOne) { Text(stringResource(R.string.remove_one)) }
                    }
                }

                // Precios
                Section(stringResource(R.string.prices))
                val p = sheet?.prices
                if (sheet == null) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    PriceTable(p, printing, currency)
                    Text(
                        if (p == null || p.updatedAt == 0L) stringResource(R.string.prices_from_catalog)
                        else stringResource(R.string.prices_updated_at,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updatedAt))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        open(details?.cardmarketUrl
                            ?: "https://www.cardmarket.com/en/Magic/Products/Search?searchString=" + Uri.encode(printing.name))
                    }) { Text("Cardmarket") }
                    OutlinedButton(onClick = {
                        open(details?.scryfallUrl ?: "https://scryfall.com/card/${printing.setCode}/${Uri.encode(printing.collectorNumber)}")
                    }) { Text("Scryfall") }
                }

                // Texto y legalidad (de Scryfall, con conexión)
                when (detailsState) {
                    DetailsState.LOADING -> {
                        Section(stringResource(R.string.card_text))
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    DetailsState.OFFLINE -> {
                        Section(stringResource(R.string.card_text))
                        Text(stringResource(R.string.details_offline), style = MaterialTheme.typography.bodySmall)
                    }
                    DetailsState.READY -> details?.let { d ->
                        Section(stringResource(R.string.card_text))
                        d.faces.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            FaceText(f, showName = d.faces.size > 1)
                        }
                        Section(stringResource(R.string.legality))
                        Legalities(d.legalities)
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

private enum class DetailsState { LOADING, READY, OFFLINE }

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

/** Imagen grande; en las cartas de dos caras, un botón para ver la otra. */
@Composable
private fun CardImage(printing: CardPrinting, twoFaced: Boolean) {
    var back by remember(printing.id) { mutableStateOf(false) }
    var hasBack by remember(printing.id) { mutableStateOf(true) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(63f / 88f).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)) {
            AsyncImage(
                model = if (back) printing.normalImageUrl.replace("/front/", "/back/") else printing.normalImageUrl,
                contentDescription = printing.name,
                contentScale = ContentScale.Fit,
                // Las cartas partidas o con aventura tienen dos caras pero una sola imagen.
                onError = { if (back) { back = false; hasBack = false } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (twoFaced && hasBack) {
            TextButton(onClick = { back = !back }) { Text(stringResource(R.string.flip_card)) }
        }
    }
}

/** Precios de Cardmarket (normal y foil) y de TCGplayer. */
@Composable
private fun PriceTable(p: CollectionStore.StoredPrices?, printing: CardPrinting, currency: Currency) {
    // Sin precios guardados todavía, los del catálogo.
    val trend = p?.eur ?: printing.prices.eur
    val trendFoil = p?.eurFoil ?: printing.prices.eurFoil
    val rows = listOf(
        stringResource(R.string.price_trend) to (trend to trendFoil),
        stringResource(R.string.price_low) to (p?.eurLow to p?.eurLowFoil),
        stringResource(R.string.price_avg30) to (p?.eurAvg30 to p?.eurAvg30Foil),
        "TCGplayer" to ((p?.usd ?: printing.prices.usd) to (p?.usdFoil ?: printing.prices.usdFoil)),
    )
    Column {
        Row {
            Text("", Modifier.weight(1.4f))
            Text(stringResource(R.string.normal), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.foil), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
        rows.forEachIndexed { i, (label, v) ->
            val cur = if (i == rows.lastIndex) Currency.USD else Currency.EUR
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                Text(formatPrice(v.first, cur), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(formatPrice(v.second, cur), Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
        }
        if (p != null && p.eurLow == null && p.eurAvg30 == null) {
            Text(stringResource(R.string.cardmarket_guide_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FaceText(f: CardDetails.Face, showName: Boolean) {
    if (showName) Text(f.name, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(f.typeLine.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        f.manaCost?.let { ManaCost(it) }
    }
    f.oracleText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
    f.flavorText?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    val stats = when {
        f.power != null && f.toughness != null -> "${f.power}/${f.toughness}"
        f.loyalty != null -> stringResource(R.string.loyalty, f.loyalty)
        else -> null
    }
    stats?.let { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
    f.artist?.let { Text(stringResource(R.string.artist, it), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
}

/** "{2}{W}{W}" como símbolos de maná: círculos del color de cada uno. */
@Composable
private fun ManaCost(cost: String) {
    val symbols = Regex("\\{([^}]+)\\}").findAll(cost).map { it.groupValues[1] }.toList()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        symbols.forEach { sym ->
            val fill = when (sym.firstOrNull { it in "WUBRG" }?.takeIf { sym.length == 1 || '/' in sym }) {
                'W' -> Color(0xFFF8F1D8)
                'U' -> Color(0xFFAAD3F0)
                'B' -> Color(0xFFBDB3AE)
                'R' -> Color(0xFFF2A68C)
                'G' -> Color(0xFF9CD3AE)
                else -> Color(0xFFCBC3BE)
            }
            Box(Modifier.size(20.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                Text(sym.replace("/", ""), color = Color.Black, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Legalities(legal: Map<String, String>) {
    Column {
        CardDetails.FORMATS.forEach { format ->
            val status = legal[format] ?: return@forEach
            val (label, color) = when (status) {
                "legal" -> stringResource(R.string.legal) to Color(0xFF2F8F4E)
                "banned" -> stringResource(R.string.banned) to Color(0xFFD9443A)
                "restricted" -> stringResource(R.string.restricted) to Color(0xFFE0A030)
                else -> stringResource(R.string.not_legal) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(format.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(label, color = color, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun rarityLabel(r: String) = when (r) {
    "common" -> stringResource(R.string.rarity_common)
    "uncommon" -> stringResource(R.string.rarity_uncommon)
    "rare" -> stringResource(R.string.rarity_rare)
    "mythic" -> stringResource(R.string.rarity_mythic)
    else -> r
}
