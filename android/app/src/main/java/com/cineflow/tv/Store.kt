package com.cineflow.tv

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent settings. Mirrors the keys of Electron's settings.json
 * (electron/main.cjs readSettings/writeSettings).
 */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cineflow", Context.MODE_PRIVATE)

    var tmdbCredential: String?
        get() = prefs.getString("tmdbCredential", null)
        set(v) = prefs.edit().putString("tmdbCredential", v).apply()

    var proxy: String
        get() = prefs.getString("proxy", "system") ?: "system"
        set(v) = prefs.edit().putString("proxy", v.ifBlank { "system" }).apply()

    var resourceMode: String
        get() = prefs.getString("resourceMode", "stable") ?: "stable"
        set(v) = prefs.edit().putString("resourceMode", v).apply()

    var resourcePlaybackMode: String
        get() = prefs.getString("resourcePlaybackMode", "auto") ?: "auto"
        set(v) = prefs.edit().putString("resourcePlaybackMode", v).apply()

    var playbackProxyMode: String
        get() = prefs.getString("playbackProxyMode", "follow") ?: "follow"
        set(v) = prefs.edit().putString("playbackProxyMode", v).apply()

    var customResourceSources: String
        get() = prefs.getString("customResourceSources", "[]") ?: "[]"
        set(v) = prefs.edit().putString("customResourceSources", v).apply()

    var resourceConfigRevision: Long
        get() = prefs.getLong("resourceConfigRevision", 0L)
        set(v) = prefs.edit().putLong("resourceConfigRevision", v).apply()

    fun touchResourceConfig() {
        val now = System.currentTimeMillis()
        resourceConfigRevision = maxOf(now, resourceConfigRevision + 1)
    }
}
