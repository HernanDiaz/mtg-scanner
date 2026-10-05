package com.hernan.mtgscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Miniatura de carta con tamaño fijo (proporción 63×88). El tamaño debe ser
 * fijo: mientras la imagen carga (o si no hay conexión) AsyncImage no conoce
 * su alto y, sin límite, se estira hasta ocupar toda la pantalla.
 */
@Composable
fun CardThumb(url: String, width: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(width * 0.06f)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(width, width * 88f / 63f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
    )
}

