package com.hernan.mtgscanner.ios

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.hernan.mtgscanner.data.USER_AGENT
import com.hernan.mtgscanner.ui.IosServices
import com.hernan.mtgscanner.ui.MtgScannerApp
import com.hernan.mtgscanner.ui.ScannerScreen
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import platform.UIKit.UIViewController

private val services: IosServices by lazy { IosServices() }

/** Pantalla principal de la app; la crea el SceneDelegate de Swift. Todo es común con Android (módulo shared). */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    // Imágenes de cartas: Scryfall rechaza los User-Agent genéricos.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { HttpClient { defaultRequest { header("User-Agent", USER_AGENT) } } })) }
            .crossfade(true)
            .build()
    }
    MtgScannerApp(services) { ScannerScreen() }
}
