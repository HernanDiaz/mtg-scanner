package com.hernan.mtgscanner.data

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver

/** La colección de la app Android, en el mismo fichero que usaba SQLiteOpenHelper. */
fun CollectionStore(context: Context, dbName: String = "collection.db"): CollectionStore {
    val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
    return CollectionStore(Db(AndroidSQLiteDriver().open(file.path)))
}
