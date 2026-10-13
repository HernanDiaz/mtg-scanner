package com.hernan.mtgscanner.ui

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier

/**
 * Campo de texto de la app. Usa el estado de texto de Compose (TextFieldState), que el
 * propio campo mantiene sincronizado con el teclado, en vez de un String que se devuelve en
 * cada cambio: en iPhone, con el autocorrector y las sugerencias del teclado, ese ida y vuelta
 * hacía saltar el cursor y perder letras ("sandra" -> "andras").
 *
 * Se usa como OutlinedTextField: [value] y [onValueChange]. Si [value] cambia desde fuera
 * (el botón de borrar), el campo lo toma.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val state = rememberTextFieldState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    // Lo último que el campo mandó fuera (o recibió de fuera).
    val last = remember { arrayOf(value) }
    // Del campo hacia fuera: cada texto nuevo que escribe el usuario.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != last[0]) {
                last[0] = text
                currentOnChange(text)
            }
        }
    }
    // De fuera hacia el campo: solo un cambio que no venga del propio campo (el botón de
    // borrar). Un valor que llega con retraso mientras se escribe no hace volver atrás el texto.
    LaunchedEffect(value) {
        if (value != last[0]) {
            last[0] = value
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    OutlinedTextField(
        state = state,
        modifier = modifier,
        label = label?.let { l -> { l() } },
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
    )
}
