package com.hernan.mtgscanner.data

import kotlin.math.abs
import kotlin.math.round

/** Descomposición Unicode NFKD ("Relámpago" -> "Rela\u0301mpago"), como unicodedata en Python. */
expect fun nfkd(s: String): String

/** Caché que pueden usar varios hilos a la vez. */
expect class ConcurrentCache<K : Any, V : Any>() {
    fun getOrPut(key: K, compute: () -> V): V
    fun getIfPresent(key: K): V?
}

/** "1.50": número con [decimals] decimales y punto, sin depender del idioma del móvil. */
fun Double.fixed(decimals: Int): String {
    if (isNaN()) return "NaN"
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(abs(this) * factor).toLong()
    val int = scaled / factor
    val frac = (scaled % factor).toString().padStart(decimals, '0')
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$int" else "$sign$int.$frac"
}

/** Texto en Windows-1252 (lo que guarda Excel en Windows). */
fun decodeWindows1252(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(if (v in 0x80..0x9F) CP1252_HIGH[v - 0x80] else v.toChar())
    }
    return sb.toString()
}

/** 0x80..0x9F en Windows-1252 (el resto coincide con Latin-1). */
private val CP1252_HIGH = charArrayOf(
    '\u20AC', '\uFFFD', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
    '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\uFFFD', '\u017D', '\uFFFD',
    '\uFFFD', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
    '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\uFFFD', '\u017E', '\u0178',
)

/** Cerrojo reentrante: una sola operación a la vez en una conexión de SQLite. */
expect class DbLock() {
    fun lock()
    fun unlock()
    fun <T> withLock(block: () -> T): T
}

/** Hora actual en milisegundos desde 1970. */
expect fun nowMillis(): Long

/** El día (yyyy-MM-dd) de [millis] en la zona horaria del móvil. */
expect fun localDay(millis: Long): String

/** Fecha y hora en el formato del móvil ("5 oct 2026, 10:30"). */
expect fun formatDateTime(millis: Long): String

/** Un día "yyyy-MM-dd" en el formato del móvil: largo ("5 oct 2026") o corto ("5/10/26"). */
expect fun formatDay(day: String, long: Boolean): String

/** Número con [decimals] decimales en el formato del móvil ("1,5" en español). */
expect fun formatNumber(value: Double, decimals: Int): String
