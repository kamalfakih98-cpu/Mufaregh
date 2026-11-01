package com.kamal.transcriber

import android.content.Context

/** Simple SharedPreferences-backed settings. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var modelFile: String
        get() = prefs.getString("model", "") ?: ""
        set(v) { prefs.edit().putString("model", v).apply() }

    /** "ar", "en", "fr" or "auto". */
    var language: String
        get() = prefs.getString("lang", "ar") ?: "ar"
        set(v) { prefs.edit().putString("lang", v).apply() }

    var threads: Int
        get() = prefs.getInt("threads", 4)
        set(v) { prefs.edit().putInt("threads", v).apply() }

    /** Feed the tail of the previous chunk to the next one as context. */
    var useContext: Boolean
        get() = prefs.getBoolean("ctx", true)
        set(v) { prefs.edit().putBoolean("ctx", v).apply() }

    /** Optional hint text (names, terms, dialect sample) given to the decoder. */
    var prompt: String
        get() = prefs.getString("prompt", "") ?: ""
        set(v) { prefs.edit().putString("prompt", v).apply() }
}
