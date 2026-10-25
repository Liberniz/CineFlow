package com.cineflow.tv.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import coil.Coil
import coil.ImageLoader
import com.cineflow.tv.Net
import com.cineflow.tv.ResourceClient
import com.cineflow.tv.Store
import com.cineflow.tv.TmdbClient
import com.cineflow.tv.toObjectList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject

/** Shared singletons for the native UI (the data layer is UI-agnostic). */
object Graph {
    lateinit var store: Store
    lateinit var tmdb: TmdbClient
    lateinit var resources: ResourceClient

    @Volatile
    private var ready = false

    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        val app = context.applicationContext
        store = Store(app)
        tmdb = TmdbClient(store)
        resources = ResourceClient(store)
        ready = true
    }
}

/** Coil setup: posters/backdrops go through the app's proxy setting too. */
object Images {
    fun applyLoader(context: Context) {
        val loader = ImageLoader.Builder(context.applicationContext)
            .callFactory { Net.client(Graph.store.proxy) }
            .crossfade(false)
            .build()
        Coil.setImageLoader(loader)
    }

    fun url(path: String?, size: String = "w342"): String? {
        val p = path?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$p"
    }
}

/** Favorites backed by Store.favoritesJson (native-only; v5.0+). */
object Favorites {
    fun all(): List<JSONObject> = runCatching {
        JSONArray(Graph.store.favoritesJson).toObjectList()
    }.getOrDefault(emptyList())

    fun contains(mediaType: String, id: Long): Boolean =
        all().any { it.optString("mediaType") == mediaType && it.optLong("id") == id }

    /** Returns true when the movie is a favorite after the toggle. */
    fun toggle(movie: JSONObject): Boolean {
        val mt = movie.optString("mediaType")
        val id = movie.optLong("id")
        val list = all().toMutableList()
        val idx = list.indexOfFirst {
            it.optString("mediaType") == mt && it.optLong("id") == id
        }
        val nowFav = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(0, movie)
            true
        }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        Graph.store.favoritesJson = arr.toString()
        return nowFav
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.toastShort(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Context.toastLong(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/** Gentle scale-up while focused — the classic TV affordance. */
fun View.attachFocusScale(scale: Float = 1.07f) {
    setOnFocusChangeListener { v, hasFocus ->
        v.animate()
            .scaleX(if (hasFocus) scale else 1f)
            .scaleY(if (hasFocus) scale else 1f)
            .setDuration(130)
            .start()
    }
}

fun newUiScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: "未知错误"

/** "2024 · ★8.1 · 剧集" style meta line for a compact movie object. */
fun JSONObject.movieMeta(): String {
    val parts = ArrayList<String>()
    optString("releaseDate").take(4).takeIf { it.isNotBlank() }?.let { parts.add(it) }
    val rating = optDouble("voteAverage", 0.0)
    if (rating > 0) parts.add("★" + String.format("%.1f", rating))
    if (optString("mediaType") == "tv") parts.add("剧集")
    return parts.joinToString(" · ")
}

/**
 * Grid geometry shared by Browse/Search/Favorites: how many poster
 * columns fit next to the 172dp nav rail, and the exact item width.
 * Returns (spanCount, itemWidthPx).
 */
fun Context.gridGeometry(): Pair<Int, Int> {
    val metrics = resources.displayMetrics
    val contentDp = metrics.widthPixels / metrics.density - 172f - 48f
    val span = (contentDp / 138f).toInt().coerceIn(3, 7)
    val itemPx = ((metrics.widthPixels - dp(172) - dp(48)) / span)
    return span to itemPx
}
