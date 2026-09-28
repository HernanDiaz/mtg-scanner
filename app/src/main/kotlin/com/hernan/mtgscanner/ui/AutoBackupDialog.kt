package com.hernan.mtgscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hernan.mtgscanner.R
import com.hernan.mtgscanner.data.AutoBackup
import java.text.DateFormat
import java.util.Date

/**
 * Copia automática: explica cómo funciona, deja elegir el fichero (en Drive, Dropbox,
 * el móvil...), muestra la última copia y cómo restaurarla.
 */
@Composable
fun AutoBackupDialog(
    state: AutoBackup.State,
    onChooseFile: () -> Unit,
    onBackupNow: () -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.file != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_backup)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.auto_backup_explain), style = MaterialTheme.typography.bodyMedium)
                if (!active) {
                    Button(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auto_backup_choose))
                    }
                } else {
                    Text(
                        stringResource(R.string.auto_backup_file, state.fileName ?: AutoBackup.FILE_NAME),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.lastSaved > 0) {
                            stringResource(R.string.auto_backup_last, formatTime(state.lastSaved), cardsLabel(state.lastCards))
                        } else {
                            stringResource(R.string.auto_backup_never)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.lastChecked - state.lastSaved >= 60_000) {
                        Text(
                            stringResource(R.string.auto_backup_checked, formatTime(state.lastChecked)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.lastError?.let {
                        Text(
                            stringResource(R.string.auto_backup_error, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = onBackupNow, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auto_backup_now))
                    }
                    OutlinedButton(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auto_backup_change))
                    }
                }
                Text(
                    stringResource(R.string.auto_backup_restore),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.auto_backup_android),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = if (active) {
            { TextButton(onClick = onDisable) { Text(stringResource(R.string.auto_backup_disable)) } }
        } else {
            null
        },
    )
}

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
