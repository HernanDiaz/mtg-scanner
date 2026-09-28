package com.hernan.mtgscanner

import androidx.test.platform.app.InstrumentationRegistry
import com.hernan.mtgscanner.data.Catalog
import kotlinx.coroutines.runBlocking

/**
 * El catálogo que ya carga la app al arrancar (App.catalog). Las pruebas corren en
 * el mismo proceso: un segundo catálogo no cabe en memoria.
 */
fun appCatalog(): Catalog = runBlocking {
    (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App).catalog.await()
}
