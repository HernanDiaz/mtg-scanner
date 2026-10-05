package com.hernan.mtgscanner.data

/**
 * Preferencias guardadas (clave -> valor), con la forma de SharedPreferences de Android.
 * En Android son SharedPreferences; en iPhone, NSUserDefaults.
 */
interface Prefs {
    fun getString(key: String, default: String?): String?
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putLong(key: String, value: Long): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
    }
}

/** Las preferencias [name] de cada plataforma. */
expect fun platformPrefs(name: String): Prefs
