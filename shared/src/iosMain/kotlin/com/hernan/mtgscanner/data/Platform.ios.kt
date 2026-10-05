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
