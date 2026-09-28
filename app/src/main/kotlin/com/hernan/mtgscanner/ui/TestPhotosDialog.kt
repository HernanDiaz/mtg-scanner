package com.hernan.mtgscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hernan.mtgscanner.R

/**
 * Fotos de prueba: activar el modo, cuántas hay, exportarlas (zip para compartir,
 * p. ej. a Google Drive) y borrarlas.
 */
@Composable
fun TestPhotosDialog(
    enabled: Boolean,
    count: Int,
    bytes: Long,
    exporting: Boolean,
    onEnabled: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_photos)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.test_photos_explain), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.test_photos_switch), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = enabled, onCheckedChange = onEnabled)
                }
                Text(
                    stringResource(R.string.test_photos_saved, count, String.format("%.1f", bytes / 1_048_576.0)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onExport, enabled = count > 0 && !exporting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (exporting) R.string.test_photos_exporting else R.string.test_photos_export))
                }
                if (confirmClear) {
                    Text(stringResource(R.string.test_photos_clear_confirm, count), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.test_photos_delete)) }
                    }
                } else {
                    OutlinedButton(onClick = { confirmClear = true }, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.test_photos_clear))
                    }
                }
                Text(
                    stringResource(R.string.test_photos_how),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
