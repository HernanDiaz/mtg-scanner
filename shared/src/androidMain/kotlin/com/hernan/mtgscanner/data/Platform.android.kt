package com.hernan.mtgscanner.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

actual class ConcurrentCache<K : Any, V : Any> actual constructor() {
    private val map = ConcurrentHashMap<K, V>()
    actual fun getOrPut(key: K, compute: () -> V): V = map.getOrPut(key, compute)
}
