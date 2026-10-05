package com.hernan.mtgscanner.data

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File

/**
 * El catálogo de la app Android: viene en assets/catalog.sqlite y se copia al
 * almacenamiento interno la primera vez y tras cada actualización de la app.
 */
fun openCatalog(context: Context): Catalog {
    val file = File(context.filesDir, Catalog.FILE_NAME)
    val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
    if (!file.exists() || prefs.getLong("stamp", -1) != stamp) {
        val tmp = File(context.filesDir, "${Catalog.FILE_NAME}.tmp")
        context.assets.open(Catalog.FILE_NAME).use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        tmp.renameTo(file)
        prefs.edit().putLong("stamp", stamp).apply()
    }
    return Catalog(AndroidSQLiteDriver().open(file.path))
}
