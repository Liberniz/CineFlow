package com.cineflow.tv

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native side of window.cineflow.
 *
 * The JS shim (assets/bridge-shim.js) turns every call into
 * postMessage({id, method, args}); results come back via
 * window.__cineflowResolve(id, json) / window.__cineflowReject(id, code, msg).
 *
 * Method names mirror Electron's ipcMain channels so the renderer needs no changes.
 */
class CineflowBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val store: Store,
    private val tmdb: TmdbClient,
    private val resources: ResourceClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    // JS entry points (called on a WebView background thread)
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun postMessage(payload: String) {
        var id = -1L
        scope.launch(Dispatchers.IO) {
            try {
                val msg = JSONObject(payload)
                id = msg.getLong("id")
                val method = msg.getString("method")
                val args = msg.optJSONArray("args") ?: JSONArray()
                val result = dispatch(method, args)
                resolve(id, bridgeResultToJs(result))
            } catch (e: BridgeError) {
                reject(id, e.code, e.message ?: "")
            } catch (e: Exception) {
                reject(id, "BRIDGE_ERROR", e.message ?: "unknown error")
            }
        }
    }

    @JavascriptInterface
    fun playVideo(payload: String) {
        mainHandler.post {
            try {
                val o = JSONObject(payload)
                val intent = Intent(activity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, o.optString("url"))
                    putExtra(PlayerActivity.EXTRA_KIND, o.optString("kind"))
                    putExtra(PlayerActivity.EXTRA_TITLE, o.optString("title"))
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                // ignore malformed payloads
            }
        }
    }

    private fun bridgeResultToJs(result: Any?): String = when (result) {
        null -> "null"
        is String -> JSONObject.quote(result)
        is JSONObject, is JSONArray -> result.toString()
        is Number, is Boolean -> result.toString()
        else -> JSONObject.quote(result.toString())
    }

    private fun resolve(id: Long, json: String) {
        scope.launch(Dispatchers.Main) {
            // json is embedded as a JS literal (not a string), so quotes need no escaping.
            webView.evaluateJavascript("window.__cineflowResolve($id,$json);", null)
        }
    }

    private fun reject(id: Long, code: String, message: String) {
        scope.launch(Dispatchers.Main) {
            val safe = JSONObject.quote(message)
            val safeCode = JSONObject.quote(code)
            webView.evaluateJavascript("window.__cineflowReject($id,$safeCode,$safe);", null)
        }
    }

    // ------------------------------------------------------------------
    // dispatch
    // ------------------------------------------------------------------

    private suspend fun dispatch(method: String, args: JSONArray): Any? {
        return when (method) {
            // --- settings / storage ---
            "app:getCredentialState" -> publicCredentialState()
            "app:saveCredential" -> {
                store.tmdbCredential = args.optString(0, "").ifBlank { null }
                tmdb.clearCache()
                publicCredentialState()
            }
            "app:clearCredential" -> {
                store.tmdbCredential = null
                tmdb.clearCache()
                publicCredentialState()
            }
            "app:saveProxy" -> {
                store.proxy = normalizeProxy(args.optString(0, "system"))
                tmdb.clearCache()
                publicCredentialState()
            }
            "app:getResourceSettings" -> resources.publicResourceSettings()
            "app:saveResourceMode" -> {
                val mode = args.optString(0, "stable")
                store.resourceMode = if (mode in setOf("stable", "movie", "full", "off")) mode else "stable"
                store.touchResourceConfig()
                resources.clearCache()
                resources.publicResourceSettings()
            }
            "app:saveResourcePlaybackMode" -> {
                val mode = args.optString(0, "auto")
                store.resourcePlaybackMode = if (mode in setOf("auto", "direct", "proxy")) mode else "auto"
                resources.publicResourceSettings()
            }
            "app:importResourceSources" -> {
                val payload: Any? = if (args.isNull(0)) null else args.get(0)
                resources.importResourceSources(payload)
            }
            "app:clearResourceSources" -> resources.clearResourceSources()
            "app:testConnection" -> testConnection()

            // --- TMDB ---
            "tmdb:initial" -> tmdb.getInitialData(publicCredentialState())
            "tmdb:search" -> {
                val query = args.optString(0, "")
                val options = args.optJSONObject(1) ?: JSONObject()
                tmdb.searchMovies(query, options.optInt("page", 1).coerceAtLeast(1), options.optBoolean("enrich", true))
            }
            "tmdb:discover" -> tmdb.discoverMovies(args.optJSONObject(0) ?: JSONObject())
            "tmdb:recommendByMovie" -> {
                val id = args.optLong(0, 0)
                val options = args.optJSONObject(1) ?: JSONObject()
                tmdb.recommendByMovie(id, options.optString("mediaType", "movie"), options.optInt("page", 1))
            }
            "tmdb:details" -> {
                val id = args.optLong(0, 0)
                val options = args.optJSONObject(1) ?: JSONObject()
                tmdb.getMovieDetails(id, options.optString("mediaType", "movie"))
            }

            // --- resources ---
            "resources:findMovie" -> {
                val payload = args.optJSONObject(0) ?: JSONObject()
                val options = args.optJSONObject(1) ?: JSONObject()
                resources.findMovieResources(
                    payload,
                    options.optInt("cursor", 0),
                    options.optInt("limit", 3)
                )
            }

            // --- player ---
            "player:resolveMediaUrl" -> resources.resolveMediaUrl(args.optString(0, ""))
            "player:getMediaProxyUrl" -> {
                // Identity on TV: ExoPlayer plays the URL directly, no local proxy.
                // (The shim normally intercepts this call before it reaches here.)
                args.optString(0, "")
            }
            "player:getPlaybackProxyMode" -> playbackProxyStatus()
            "player:setPlaybackProxyMode" -> {
                val mode = args.optString(0, "follow")
                store.playbackProxyMode = if (mode in setOf("follow", "proxy", "direct")) mode else "follow"
                playbackProxyStatus()
            }
            "meta:getStatus" -> null

            // --- window / island: desktop-only, no-ops on TV ---
            "window:minimize", "window:enterIsland", "window:expandIsland",
            "window:restoreNormal", "window:dockIsland",
            "window:islandDragBegin", "window:islandDragMove", "window:islandDragEnd",
            "window:maximize", "window:close" -> null

            // --- shell ---
            "shell:openExternal" -> {
                openExternal(args.optString(0, ""))
                null
            }

            else -> throw BridgeError("UNKNOWN_METHOD", "unknown bridge method: $method")
        }
    }

    // ------------------------------------------------------------------
    // helpers mirroring main.cjs public shapes
    // ------------------------------------------------------------------

    private fun normalizeProxy(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty() || v == "system" || v == "direct") return v.ifEmpty { "system" }
        // Accept http(s)://host:port and socks5://host:port (mirrors Net.parseProxy).
        return if (Regex("^(https?|socks5)://[^/\\s]+(:\\d+)?(/.*)?\$", RegexOption.IGNORE_CASE).matches(v)) v else "system"
    }

    private fun credentialPreview(value: String): String {
        return if (value.length <= 10) "••••••" else value.take(4) + "••••" + value.takeLast(4)
    }

    fun publicCredentialState(): JSONObject {
        val raw = store.tmdbCredential?.trim().orEmpty()
        val configured = raw.isNotEmpty()
        val type = if (raw.startsWith("eyJ")) "readToken" else "apiKey"
        val proxyMode = store.proxy
        return jsonOf(
            "configured" to configured,
            "source" to "local-settings",
            "type" to type,
            "preview" to if (configured) credentialPreview(raw) else "",
            "proxy" to jsonOf(
                "configured" to (proxyMode != "system"),
                "mode" to proxyMode,
                "value" to proxyMode
            ),
            "resources" to resources.publicResourceSettings()
        )
    }

    private suspend fun testConnection(): JSONObject {
        val start = System.currentTimeMillis()
        tmdb.tmdbFetch("/configuration")
        val elapsed = System.currentTimeMillis() - start
        val proxyMode = store.proxy
        return jsonOf(
            "ok" to true,
            "elapsedMs" to elapsed,
            "proxy" to jsonOf("configured" to (proxyMode != "system"), "mode" to proxyMode, "value" to proxyMode),
            "resolvedProxy" to proxyMode,
            "imagesBaseUrl" to "https://image.tmdb.org/t/p/"
        )
    }

    private fun playbackProxyStatus(): JSONObject {
        val mode = store.playbackProxyMode
        return jsonOf(
            "mode" to mode,
            "modes" to JSONArray(listOf(
                jsonOf("value" to "follow", "label" to "跟随全局代理", "description" to "跟随系统代理设置。"),
                jsonOf("value" to "proxy", "label" to "始终走代理", "description" to "播放始终经过代理。"),
                jsonOf("value" to "direct", "label" to "直连", "description" to "播放不经过代理。")
            )),
            "link" to "none"
        )
    }

    private fun openExternal(url: String) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // No browser on this TV: renderer toasts via its own catch.
            throw BridgeError("OPEN_EXTERNAL_FAILED", "无法打开外部链接，电视上可能没有安装浏览器")
        }
    }
}
