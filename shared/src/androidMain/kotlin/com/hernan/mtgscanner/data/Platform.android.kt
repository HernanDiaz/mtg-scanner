package com.hernan.mtgscanner.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

actual class ConcurrentCache<K : Any, V : Any> actual constructor() {
    private val map = ConcurrentHashMap<K, V>()
    actual fun getOrPut(key: K, compute: () -> V): V = map.getOrPut(key, compute)
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun localDay(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(millis))

actual class DbLock actual constructor() {
    private val lock = java.util.concurrent.locks.ReentrantLock()
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
