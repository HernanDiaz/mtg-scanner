package com.hernan.mtgscanner.data

import androidx.sqlite.driver.NativeSQLiteDriver
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * La colección de la app de iPhone, en la carpeta de documentos de la app (la que
 * guarda la copia de iCloud del iPhone).
 */
fun openCollection(fileName: String = "collection.db"): CollectionStore {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    return CollectionStore(Db(NativeSQLiteDriver().open("$docs/$fileName")))
}
