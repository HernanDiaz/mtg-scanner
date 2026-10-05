package com.hernan.mtgscanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource

/**
 * La app: escanear, colección y lista de deseos. El escáner es de cada plataforma
 * (cámara y OCR propios); el resto es común.
 */
@Composable
fun MtgScannerApp(services: AppServices, scanner: @Composable () -> Unit) {
    CompositionLocalProvider(LocalServices provides services) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Search, null) },
                            label = { Text(stringResource(Res.string.tab_scan)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                            label = { Text(stringResource(Res.string.tab_collection)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Favorite, null) },
                            label = { Text(stringResource(Res.string.tab_wishlist)) },
                        )
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (tab) {
                        0 -> scanner()
                        1 -> CollectionScreen()
                        else -> WishlistScreen()
                    }
                }
            }
        }
    }
}
