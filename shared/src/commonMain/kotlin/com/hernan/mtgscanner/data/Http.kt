package com.hernan.mtgscanner.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

/** Scryfall rechaza (400) los User-Agent genéricos: la app se identifica. */
const val USER_AGENT = "MTGScanner/0.1"

/** Cliente HTTP de la app (OkHttp en Android, NSURLSession en iPhone). */
val http: HttpClient by lazy {
    HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
        }
        defaultRequest {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json")
        }
    }
}
