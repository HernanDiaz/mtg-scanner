package com.hernan.mtgscanner.data

import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfkd(s: String): String = (s as NSString).decomposedStringWithCompatibilityMapping

actual class ConcurrentCache<K : Any, V : Any> actual constructor() {
    private val map = HashMap<K, V>()
    private val lock = NSLock()

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
}

actual fun nowMillis(): Long = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun localDay(millis: Long): String {
    val formatter = platform.Foundation.NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
        locale = platform.Foundation.NSLocale(localeIdentifier = "en_US_POSIX")
    }
    return formatter.stringFromDate(platform.Foundation.NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
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
