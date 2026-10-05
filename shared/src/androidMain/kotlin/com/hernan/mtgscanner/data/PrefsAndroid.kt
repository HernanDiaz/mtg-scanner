package com.hernan.mtgscanner.data

import android.content.Context
import android.content.SharedPreferences

/** El contexto de la app, para las preferencias (lo pone App al arrancar). */
lateinit var appContext: Context

actual fun platformPrefs(name: String): Prefs = SharedPrefs(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))

class SharedPrefs(private val p: SharedPreferences) : Prefs {
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun edit(): Prefs.Editor = object : Prefs.Editor {
        val e = p.edit()
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun clear() = apply { e.clear() }
        override fun apply() = e.apply()
    }
}
