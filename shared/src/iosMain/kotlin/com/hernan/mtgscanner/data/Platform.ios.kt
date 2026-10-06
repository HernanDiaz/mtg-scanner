package com.hernan.mtgscanner.data

import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.timeIntervalSince1970

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfkd(s: String): String = (s as NSString).decomposedStringWithCompatibilityMapping

actual class ConcurrentCache<K : Any, V : Any> actual constructor() {
    private val map = HashMap<K, V>()
    private val lock = NSLock()

    actual fun getIfPresent(key: K): V? {
        lock.lock()
        try {
            return map[key]
        } finally {
            lock.unlock()
        }
    }

    actual fun getOrPut(key: K, compute: () -> V): V {
        lock.lock()
        try {
            map[key]?.let { return it }
        } finally {
            lock.unlock()
        }
        // Se calcula fuera del cerrojo: si dos hilos lo calculan a la vez, da lo mismo.
        val value = compute()
        lock.lock()
        try {
            return map.getOrPut(key) { value }
        } finally {
            lock.unlock()
        }
    }
    actual fun size(): Int {
        lock.lock()
        try {
            return map.size
        } finally {
            lock.unlock()
        }
    }

    actual fun clear() {
        lock.lock()
        try {
            map.clear()
        } finally {
            lock.unlock()
        }
    }
}

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun localDay(millis: Long): String {
    val formatter = platform.Foundation.NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
        locale = platform.Foundation.NSLocale(localeIdentifier = "en_US_POSIX")
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}

actual class DbLock actual constructor() {
    private val lock = platform.Foundation.NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

actual fun formatDateTime(millis: Long): String {
    val f = platform.Foundation.NSDateFormatter().apply {
        dateStyle = platform.Foundation.NSDateFormatterMediumStyle
        timeStyle = platform.Foundation.NSDateFormatterShortStyle
    }
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}

actual fun formatDay(day: String, long: Boolean): String {
    val parser = platform.Foundation.NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
        locale = platform.Foundation.NSLocale(localeIdentifier = "en_US_POSIX")
    }
    val date = parser.dateFromString(day) ?: return day
    val f = platform.Foundation.NSDateFormatter().apply {
        dateStyle = if (long) platform.Foundation.NSDateFormatterMediumStyle else platform.Foundation.NSDateFormatterShortStyle
        timeStyle = platform.Foundation.NSDateFormatterNoStyle
    }
    return f.stringFromDate(date)
}

actual fun formatNumber(value: Double, decimals: Int): String {
    val f = platform.Foundation.NSNumberFormatter().apply {
        numberStyle = platform.Foundation.NSNumberFormatterDecimalStyle
        minimumFractionDigits = decimals.toULong()
        maximumFractionDigits = decimals.toULong()
        usesGroupingSeparator = false
    }
    return f.stringFromNumber(platform.Foundation.NSNumber(value)) ?: value.fixed(decimals)
}
