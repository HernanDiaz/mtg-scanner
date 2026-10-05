package com.hernan.mtgscanner.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * Base de datos de la colección sobre el SQLite multiplataforma (androidx.sqlite), con
 * las mismas operaciones que SQLiteDatabase de Android: así CollectionStore es igual en
 * Android e iPhone, y en Android sigue usando el mismo fichero que antes.
 *
 * Una sola conexión: cada operación toma el cerrojo, y una transacción lo mantiene
 * hasta terminar (como SQLiteDatabase, admite transacciones anidadas).
 */
class Db(private val conn: SQLiteConnection) : AutoCloseable {
    private val lock = DbLock()
    /** Una entrada por transacción abierta (anidadas): si se marcó como buena. */
    private val levels = ArrayList<Boolean>()
    private var failed = false

    /** Igual que SQLiteOpenHelper: crea o actualiza el esquema según PRAGMA user_version. */
    fun migrate(version: Int, onCreate: (Db) -> Unit, onUpgrade: (Db, old: Int, new: Int) -> Unit) {
        val current = rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (current == version) return
        beginTransaction()
        try {
            if (current == 0) onCreate(this) else onUpgrade(this, current, version)
            execSQL("PRAGMA user_version = $version")
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    // ------------------------------------------------------------------ transacciones

    fun beginTransaction() {
        lock.lock()
        if (levels.isEmpty()) {
            failed = false
            conn.exec("BEGIN IMMEDIATE")
        }
        levels.add(false)
    }

    fun setTransactionSuccessful() {
        levels[levels.lastIndex] = true
    }

    /** Si alguna transacción (también una interna) no se marcó como buena, se deshace todo. */
    fun endTransaction() {
        try {
            if (!levels.removeAt(levels.lastIndex)) failed = true
            if (levels.isEmpty()) conn.exec(if (failed) "ROLLBACK" else "COMMIT")
        } finally {
            lock.unlock()
        }
    }

    // ------------------------------------------------------------------ consultas

    /** Las filas de [sql] ya leídas (los argumentos van como texto, igual que en Android). */
    fun rawQuery(sql: String, args: Array<out String?>?): DbCursor = lock.withLock {
        conn.prepare(sql).use { st ->
            args?.forEachIndexed { i, a -> st.bind(i + 1, a) }
            val names = st.getColumnNames()
            val rows = ArrayList<Array<Any?>>()
            while (st.step()) rows += Array(names.size) { i -> st.value(i) }
            DbCursor(names, rows)
        }
    }

    /** SELECT * de [table] con la condición [where]. */
    fun query(table: String, columns: Array<String>?, where: String?, args: Array<out String?>?,
              groupBy: String?, having: String?, orderBy: String?): DbCursor {
        val cols = columns?.joinToString(", ") ?: "*"
        return rawQuery("SELECT $cols FROM $table" + (where?.let { " WHERE $it" } ?: ""), args)
    }

    fun execSQL(sql: String, args: Array<out Any?> = emptyArray()) {
        lock.withLock { run(sql, args) }
    }

    /** Inserta una fila y devuelve su rowid (-1 si falla, como Android). */
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long =
        runCatching { insertOrThrow(table, nullColumnHack, values) }.getOrDefault(-1L)

    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE)

    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int): Long = lock.withLock {
        val keys = values.keys()
        val or = when (conflict) {
            CONFLICT_IGNORE -> " OR IGNORE"
            CONFLICT_REPLACE -> " OR REPLACE"
            else -> ""
        }
        run("INSERT$or INTO $table (${keys.joinToString(", ")}) VALUES (${keys.joinToString(", ") { "?" }})",
            keys.map(values::get).toTypedArray())
        if (changes() == 0) -1L else lastInsertRowId()
    }

    /** Actualiza las filas de [where] y devuelve cuántas cambió. */
    fun update(table: String, values: ContentValues, where: String?, args: Array<out String?>?): Int = lock.withLock {
        val keys = values.keys()
        run("UPDATE $table SET ${keys.joinToString(", ") { "$it = ?" }}" + (where?.let { " WHERE $it" } ?: ""),
            (keys.map(values::get) + args.orEmpty().toList()).toTypedArray())
        changes()
    }

    /** Borra las filas de [where] y devuelve cuántas borró. */
    fun delete(table: String, where: String?, args: Array<out String?>?): Int = lock.withLock {
        run("DELETE FROM $table" + (where?.let { " WHERE $it" } ?: ""), args.orEmpty().map { it as Any? }.toTypedArray())
        changes()
    }

    override fun close() = lock.withLock { conn.close() }

    private fun run(sql: String, args: Array<out Any?>) {
        conn.prepare(sql).use { st ->
            args.forEachIndexed { i, a -> st.bind(i + 1, a) }
            st.step()
        }
    }

    private fun changes(): Int = conn.prepare("SELECT changes()").use { st -> st.step(); st.getLong(0).toInt() }

    private fun lastInsertRowId(): Long = conn.prepare("SELECT last_insert_rowid()").use { st -> st.step(); st.getLong(0) }

    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step() }

    private fun SQLiteStatement.bind(index: Int, value: Any?) {
        when (value) {
            null -> bindNull(index)
            is String -> bindText(index, value)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is Short -> bindLong(index, value.toLong())
            is Byte -> bindLong(index, value.toLong())
            is Boolean -> bindLong(index, if (value) 1 else 0)
            is Double -> bindDouble(index, value)
            is Float -> bindDouble(index, value.toDouble())
            is ByteArray -> bindBlob(index, value)
            else -> bindText(index, value.toString())
        }
    }

    private fun SQLiteStatement.value(i: Int): Any? = when (getColumnType(i)) {
        TYPE_INTEGER -> getLong(i)
        TYPE_FLOAT -> getDouble(i)
        TYPE_TEXT -> getText(i)
        TYPE_BLOB -> getBlob(i)
        else -> null
    }

    companion object {
        const val CONFLICT_NONE = 0
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5

        // Tipos de columna de sqlite3.h (SQLITE_INTEGER...).
        private const val TYPE_INTEGER = 1
        private const val TYPE_FLOAT = 2
        private const val TYPE_TEXT = 3
        private const val TYPE_BLOB = 4
    }
}

/** Filas de una consulta, leídas de una vez, con las operaciones de Cursor que usa la app. */
class DbCursor internal constructor(private val names: List<String>, private val rows: List<Array<Any?>>) : AutoCloseable {
    private var pos = -1

    val count: Int get() = rows.size
    val columnNames: List<String> get() = names

    fun moveToNext(): Boolean = (++pos < rows.size).also { if (!it) pos = rows.size }
    fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }

    private fun v(i: Int): Any? = rows[pos][i]

    fun isNull(i: Int) = v(i) == null
    /** Un valor nulo da "" (en Android, null: aquí solo se lee así en columnas que lo admiten). */
    fun getString(i: Int): String = when (val x = v(i)) {
        null -> ""
        is ByteArray -> x.decodeToString()
        is Double -> if (x == x.toLong().toDouble()) "${x.toLong()}.0" else x.toString()
        else -> x.toString()
    }
    fun getLong(i: Int): Long = when (val x = v(i)) {
        null -> 0L
        is Long -> x
        is Double -> x.toLong()
        is String -> x.toLongOrNull() ?: x.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }
    fun getInt(i: Int): Int = getLong(i).toInt()
    fun getDouble(i: Int): Double = when (val x = v(i)) {
        null -> 0.0
        is Long -> x.toDouble()
        is Double -> x
        is String -> x.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
    fun getBlob(i: Int): ByteArray = when (val x = v(i)) {
        is ByteArray -> x
        is String -> x.encodeToByteArray()
        else -> error("columna $i: no es un blob")
    }
    fun getColumnIndex(name: String) = names.indexOf(name)

    /** La fila actual como valores (para volver a insertarla: deshacer un borrado). */
    fun rowValues(): ContentValues = ContentValues().also { cv -> names.forEachIndexed { i, n -> cv.put(n, v(i)) } }

    override fun close() {}
}

/** Valores de una fila por columna, como ContentValues de Android. */
class ContentValues {
    private val map = LinkedHashMap<String, Any?>()

    fun put(key: String, value: Any?) { map[key] = value }
    fun putNull(key: String) { map[key] = null }
    fun get(key: String): Any? = map[key]
    fun keys(): List<String> = map.keys.toList()
    fun size() = map.size

    override fun equals(other: Any?) = other is ContentValues && other.map.keys == map.keys &&
        map.all { (k, v) -> v.contentEqualsAny(other.map[k]) }
    override fun hashCode() = map.keys.hashCode()
}

private fun Any?.contentEqualsAny(other: Any?) =
    if (this is ByteArray && other is ByteArray) contentEquals(other) else this == other
