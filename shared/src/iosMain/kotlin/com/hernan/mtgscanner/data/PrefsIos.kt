package com.hernan.mtgscanner.data

import platform.Foundation.NSUserDefaults

/** NSUserDefaults con un prefijo por grupo de preferencias ("settings.currency"). */
actual fun platformPrefs(name: String): Prefs = UserDefaultsPrefs(name)

private class UserDefaultsPrefs(private val name: String) : Prefs {
    private val d = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$name.$key"
    private fun has(key: String) = d.objectForKey(k(key)) != null

    override fun getString(key: String, default: String?) = d.stringForKey(k(key)) ?: default
    override fun getLong(key: String, default: Long) = if (has(key)) d.integerForKey(k(key)) else default
    override fun getInt(key: String, default: Int) = if (has(key)) d.integerForKey(k(key)).toInt() else default
    override fun getBoolean(key: String, default: Boolean) = if (has(key)) d.boolForKey(k(key)) else default

    override fun edit(): Prefs.Editor = object : Prefs.Editor {
        val ops = ArrayList<() -> Unit>()
        override fun putString(key: String, value: String?) = apply {
            ops += { if (value == null) d.removeObjectForKey(k(key)) else d.setObject(value, k(key)) }
        }
        override fun putLong(key: String, value: Long) = apply { ops += { d.setInteger(value, k(key)) } }
        override fun putInt(key: String, value: Int) = apply { ops += { d.setInteger(value.toLong(), k(key)) } }
        override fun putBoolean(key: String, value: Boolean) = apply { ops += { d.setBool(value, k(key)) } }
        override fun remove(key: String) = apply { ops += { d.removeObjectForKey(k(key)) } }
        override fun clear() = apply {
            ops += {
                d.dictionaryRepresentation().keys.filterIsInstance<String>()
                    .filter { it.startsWith("$name.") }.forEach(d::removeObjectForKey)
            }
        }
        override fun apply() = ops.forEach { it() }
    }
}
