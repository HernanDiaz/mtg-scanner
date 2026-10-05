package com.hernan.mtgscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hernan.mtgscanner.data.AutoBackupState



/**
 * Copia automática: explica cómo funciona, deja elegir el fichero (en Drive, Dropbox,
 * el móvil...), muestra la última copia y cómo restaurarla.
 */
@Composable
fun AutoBackupDialog(
    state: AutoBackupState,
    onChooseFile: () -> Unit,
    onBackupNow: () -> Unit,
    onDisable: () -> Unit,
    /** Incluir fotos: true pide crear el zip; false deja de copiarlas. */
    onPhotos: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.file != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.auto_backup)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(if (isIos) Res.string.auto_backup_explain_ios else Res.string.auto_backup_explain), style = MaterialTheme.typography.bodyMedium)
                if (!active) {
                    Button(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.auto_backup_choose))
                    }
                } else {
                    Text(
                        stringResource(Res.string.auto_backup_file, state.fileName ?: AutoBackupState.FILE_NAME),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.lastSaved > 0) {
                            stringResource(Res.string.auto_backup_last, formatTime(state.lastSaved), cardsLabel(state.lastCards))
                        } else {
                            stringResource(Res.string.auto_backup_never)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.lastChecked - state.lastSaved >= 60_000) {
                        Text(
                            stringResource(Res.string.auto_backup_checked, formatTime(state.lastChecked)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.lastError?.let {
                        Text(
                            stringResource(Res.string.auto_backup_error, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(Modifier.fillMaxWidth().clickable { onPhotos(state.photosFile == null) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.photosFile != null, onCheckedChange = { onPhotos(it) })
                        Text(stringResource(Res.string.auto_backup_photos), Modifier.weight(1f))
                    }
                    if (state.photosFile != null) {
                        Text(
                            stringResource(Res.string.auto_backup_photos_file, state.photosFileName ?: AutoBackupState.PHOTOS_FILE_NAME) +
                                if (state.lastPhotosSaved > 0) {
                                    "\n" + stringResource(Res.string.auto_backup_photos_last, formatTime(state.lastPhotosSaved), state.lastPhotos)
                                } else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(Res.string.auto_backup_photos_explain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onBackupNow, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.auto_backup_now))
                    }
                    OutlinedButton(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.auto_backup_change))
                    }
                }
                Text(
                    stringResource(Res.string.auto_backup_restore),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(if (isIos) Res.string.auto_backup_ios else Res.string.auto_backup_android),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
        dismissButton = if (active) {
            { TextButton(onClick = onDisable) { Text(stringResource(Res.string.auto_backup_disable)) } }
        } else {
            null
        },
    )
}

private fun formatTime(ms: Long): String =
    com.hernan.mtgscanner.data.formatDateTime(ms)
