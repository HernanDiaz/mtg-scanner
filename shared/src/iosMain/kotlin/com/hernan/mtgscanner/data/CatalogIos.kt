package com.hernan.mtgscanner.data

import androidx.sqlite.driver.NativeSQLiteDriver

/** El catálogo de la app de iPhone: se lee directamente del paquete de la app (solo lectura). */
fun openCatalog(path: String): Catalog = Catalog(NativeSQLiteDriver().open(path, SQLITE_OPEN_READONLY))

/** SQLITE_OPEN_READONLY de sqlite3.h. */
private const val SQLITE_OPEN_READONLY = 0x00000001
