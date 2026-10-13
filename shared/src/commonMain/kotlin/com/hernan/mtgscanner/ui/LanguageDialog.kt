package com.hernan.mtgscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/** Idioma de la app: el del móvil o uno de [APP_LANGUAGES]. Se aplica al momento. */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val settings = LocalServices.current.settings
    val current by settings.appLanguage.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val options = listOf<Pair<String?, String>>(null to stringResource(Res.string.language_system)) + APP_LANGUAGES
                for ((code, name) in options) {
                    Row(
                        Modifier.fillMaxWidth().clickable { settings.setAppLanguage(code); onDismiss() }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == code, onClick = { settings.setAppLanguage(code); onDismiss() })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}
