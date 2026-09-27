package com.hernan.mtgscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hernan.mtgscanner.R
import com.hernan.mtgscanner.data.CONDITIONS

/** Nombre de cada estado de Cardmarket. */
val CONDITION_NAMES = mapOf(
    "M" to R.string.cond_m, "NM" to R.string.cond_nm, "EX" to R.string.cond_ex, "GD" to R.string.cond_gd,
    "LP" to R.string.cond_lp, "PL" to R.string.cond_pl, "PO" to R.string.cond_po,
)

/**
 * "Valor según el estado": explica cómo se calcula y deja activarlo y cambiar el
 * porcentaje de cada estado.
 */
@Composable
fun ConditionPricingDialog(
    enabled: Boolean,
    factors: Map<String, Int>,
    onEnabled: (Boolean) -> Unit,
    onFactor: (String, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.condition_pricing)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.condition_pricing_explain), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.condition_pricing_switch), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = enabled, onCheckedChange = onEnabled)
                }
                CONDITIONS.forEach { c ->
                    val pct = factors[c] ?: 100
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c, fontWeight = FontWeight.Bold)
                            Text(stringResource(CONDITION_NAMES.getValue(c)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onFactor(c, pct - 5) }, enabled = enabled && pct > 0) { Text("−") }
                        Text("$pct %", Modifier.width(56.dp), textAlign = TextAlign.Center,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onFactor(c, pct + 5) }, enabled = enabled && pct < 100) { Text("+") }
                    }
                }
                Text(stringResource(R.string.condition_pricing_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onReset, enabled = enabled) { Text(stringResource(R.string.reset)) } },
    )
}
