package com.hernan.mtgscanner.ui

import org.jetbrains.compose.resources.StringResource
import com.hernan.mtgscanner.data.CollectionGroup
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalUriHandler
import com.hernan.mtgscanner.data.formatDateTime
import io.ktor.http.encodeURLParameter
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.hernan.mtgscanner.data.CONDITIONS
import com.hernan.mtgscanner.data.CardDetails
import com.hernan.mtgscanner.data.CardPrinting
import com.hernan.mtgscanner.data.CollectionStore
import com.hernan.mtgscanner.data.Currency

/** Lo que la ficha lee de la colección: precios guardados, copias de la carta y fotos de las copias. */
/** [photos]: rutas de las fotos de las copias (ver CardPhotoFiles). */
class CardSheet(val prices: CollectionStore.StoredPrices?, val copiesOfCard: Int, val photos: List<String> = emptyList())

/**
 * Ficha de una carta: imagen grande, precios, texto, legalidad y enlaces. Si se
 * abre desde una fila de la colección ([group]), también sus copias y acciones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardDetailDialog(
    printing: CardPrinting,
    group: CollectionGroup?,
    deckName: String?,
    currency: Currency,
    loadSheet: suspend () -> CardSheet,
    loadDetails: suspend () -> CardDetails?,
    onCondition: (String) -> Unit = {},
    onFoil: (Boolean) -> Unit = {},
    onChangeEdition: () -> Unit = {},
    onMove: () -> Unit = {},
    onRemoveOne: () -> Unit = {},
    /** Añadir una copia más a la lista de deseos; null = sin botón. */
    onAddWish: (() -> Unit)? = null,
    /** Porcentaje del precio por el estado de la copia; null si no se valora según el estado. */
    conditionFactor: Int? = null,
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
    val uriHandler = LocalUriHandler.current
    fun open(url: String) = runCatching { uriHandler.openUri(url) }

    // Dentro del diálogo no llegan los márgenes de las barras del sistema: se miden fuera. El
    // diálogo empieza bajo la barra de estado pero mide toda la pantalla: sobra también ese alto.
    val bottomBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(bottom = bottomBar).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(printing.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(Res.string.close)) }
                }
                Text(
                    listOfNotNull(printing.setName, "${printing.setCode.uppercase()} #${printing.collectorNumber}",
                        printing.releasedAt?.take(4), printing.rarity?.let { rarityLabel(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )

                CardImage(printing, twoFaced = (details?.faces?.size ?: 0) > 1)

                // En tu colección
                if (group != null) {
                    Section(stringResource(Res.string.in_your_collection))
                    val parts = listOfNotNull(
                        stringResource(Res.string.copies_of_edition, group.quantity),
                        sheet?.let { stringResource(Res.string.copies_of_card, it.copiesOfCard) },
                        languageName(group.lang),
                        deckName?.let { stringResource(Res.string.deck_named, it) },
                    )
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = group.foil, onClick = { onFoil(!group.foil) }, label = { Text(stringResource(Res.string.foil)) })
                        CONDITIONS.forEach { c ->
                            FilterChip(selected = group.condition == c, onClick = { onCondition(c) }, label = { Text(c) })
                        }
                    }
                    FlowRow {
                        TextButton(onClick = onChangeEdition) { Text(stringResource(Res.string.change_edition)) }
                        TextButton(onClick = onMove) { Text(stringResource(Res.string.move_to_deck)) }
                        TextButton(onClick = onRemoveOne) { Text(stringResource(Res.string.remove_one)) }
                    }
                    // Fotos de las copias, tal como se escanearon (ver CardPhotos).
                    val photos = sheet?.photos.orEmpty()
                    if (photos.isNotEmpty()) {
                        Text(
                            pluralStringResource(Res.plurals.your_photos, photos.size, photos.size),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            photos.forEach { f ->
                                AsyncImage(
                                    model = f.toPath(), contentDescription = stringResource(Res.string.your_photo),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.height(320.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                    }
                }

                // Precios
                Section(stringResource(Res.string.prices))
                val p = sheet?.prices
                if (sheet == null) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    PriceTable(p, printing, currency)
                    if (group != null && conditionFactor != null) {
                        Text(
                            stringResource(Res.string.your_copy_value, group.condition, conditionFactor,
                                formatPrice(group.unitPrice, currency)),
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        if (p == null || p.updatedAt == 0L) stringResource(Res.string.prices_from_catalog)
                        else stringResource(Res.string.prices_updated_at,
                            formatDateTime(p.updatedAt)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        open(details?.cardmarketUrl
                            ?: "https://www.cardmarket.com/en/Magic/Products/Search?searchString=" + printing.name.encodeURLParameter())
                    }) { Text("Cardmarket") }
                    OutlinedButton(onClick = {
                        open(details?.scryfallUrl ?: "https://scryfall.com/card/${printing.setCode}/${printing.collectorNumber.encodeURLParameter()}")
                    }) { Text("Scryfall") }
                }
                onAddWish?.let { add ->
                    var done by remember(printing.id) { mutableStateOf(false) }
                    OutlinedButton(onClick = { add(); done = true }, enabled = !done, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(if (done) Res.string.added_to_wishlist_short else Res.string.add_to_wishlist))
                    }
                }

                // Texto y legalidad (de Scryfall, con conexión)
                when (detailsState) {
                    DetailsState.LOADING -> {
                        Section(stringResource(Res.string.card_text))
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    DetailsState.OFFLINE -> {
                        Section(stringResource(Res.string.card_text))
                        Text(stringResource(Res.string.details_offline), style = MaterialTheme.typography.bodySmall)
                    }
                    DetailsState.READY -> details?.let { d ->
                        Section(stringResource(Res.string.card_text))
                        d.faces.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            FaceText(f, showName = d.faces.size > 1)
                        }
                        Section(stringResource(Res.string.legality))
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
            TextButton(onClick = { back = !back }) { Text(stringResource(Res.string.flip_card)) }
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
        stringResource(Res.string.price_trend) to (trend to trendFoil),
        stringResource(Res.string.price_low) to (p?.eurLow to p?.eurLowFoil),
        stringResource(Res.string.price_avg30) to (p?.eurAvg30 to p?.eurAvg30Foil),
        "TCGplayer" to ((p?.usd ?: printing.prices.usd) to (p?.usdFoil ?: printing.prices.usdFoil)),
    )
    Column {
        Row {
            Text("", Modifier.weight(1.4f))
            Text(stringResource(Res.string.normal), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(Res.string.foil), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
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
            Text(stringResource(Res.string.cardmarket_guide_hint), style = MaterialTheme.typography.bodySmall,
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
        f.loyalty != null -> stringResource(Res.string.loyalty, f.loyalty)
        else -> null
    }
    stats?.let { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
    f.artist?.let { Text(stringResource(Res.string.artist, it), style = MaterialTheme.typography.bodySmall,
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
                "legal" -> stringResource(Res.string.legal) to Color(0xFF2F8F4E)
                "banned" -> stringResource(Res.string.banned) to Color(0xFFD9443A)
                "restricted" -> stringResource(Res.string.restricted) to Color(0xFFE0A030)
                else -> stringResource(Res.string.not_legal) to MaterialTheme.colorScheme.onSurfaceVariant
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
    "common" -> stringResource(Res.string.rarity_common)
    "uncommon" -> stringResource(Res.string.rarity_uncommon)
    "rare" -> stringResource(Res.string.rarity_rare)
    "mythic" -> stringResource(Res.string.rarity_mythic)
    else -> r
}
