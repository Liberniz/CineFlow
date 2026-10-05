package com.cineflow.tv

import com.cineflow.tv.Net.await
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

/**
 * TMDB API client. Ports electron/main.cjs tmdbFetch/getInitialData/search/
 * discover/recommend/details, including the 5-minute in-memory cache,
 * in-flight dedup, soft timeouts, and language=zh-CN / region=CN defaults.
 *
 * Not ported (mainland-network optimisations): DoH resolver, meta-server
 * warmup, playback proxy guard. Plain HTTPS is used instead.
 */
class TmdbClient(private val store: Store) {

    data class Cred(val type: String, val value: String) // "apiKey" | "readToken"

    fun credential(): Cred? {
        val raw = store.tmdbCredential?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("eyJ")) Cred("readToken", raw) else Cred("apiKey", raw)
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    // --- cache + in-flight dedup (main.cjs: tmdbResponseCache / tmdbInFlight) ---

    private data class CacheEntry(val expiresAt: Long, val payload: JSONObject)
    private val cache = LinkedHashMap<String, CacheEntry>(220, 0.75f, true)
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<JSONObject>>()
    private val inFlightMutex = Mutex()

    suspend fun tmdbFetch(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val cred = credential()
            ?: throw BridgeError("MISSING_TMDB_CREDENTIAL", "请先在设置中填写 TMDB API Key")
        val sortedParams = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val cacheKey = "${cred.type}:${cred.value}|$path?$sortedParams&language=zh-CN"

        synchronized(cache) {
            cache[cacheKey]?.let { if (System.currentTimeMillis() < it.expiresAt) return it.payload }
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey]?.let { return@withLock it }
            val d = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                doFetch(path, params, cred)
            }
            inFlight[cacheKey] = d
            d.invokeOnCompletion { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { inFlightMutex.withLock { inFlight.remove(cacheKey) } } }
            d
        }
        return try {
            deferred.await()
        } catch (e: BridgeError) {
            throw e
        } catch (e: Exception) {
            throw BridgeError("TMDB_REQUEST_FAILED", e.message ?: "request failed")
        }
    }

    private suspend fun doFetch(path: String, params: Map<String, String>, cred: Cred): JSONObject {
        val urlBuilder = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder()
            .addQueryParameter("language", "zh-CN")
        for ((k, v) in params) urlBuilder.addQueryParameter(k, v)
        if (cred.type == "apiKey") urlBuilder.addQueryParameter("api_key", cred.value)
        val reqBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
        if (cred.type == "readToken") reqBuilder.header("Authorization", "Bearer ${cred.value}")
        val req = reqBuilder.build()

        val resp = try {
            Net.client(store.proxy).newCall(req).await()
        } catch (e: IOException) {
            throw BridgeError("TMDB_NETWORK_FAILED", "网络连接失败：${e.message}")
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            when {
                it.code == 401 -> throw BridgeError("TMDB_AUTH_FAILED", "TMDB 密钥无效")
                !it.isSuccessful -> throw BridgeError("TMDB_REQUEST_FAILED", "TMDB 请求失败：${it.code}")
            }
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw BridgeError("TMDB_REQUEST_FAILED", "TMDB 返回了无法解析的数据")
            }
            val cacheKey = "${cred.type}:${cred.value}|$path?${params.toSortedMap().entries.joinToString("&") { e -> "${e.key}=${e.value}" }}&language=zh-CN"
            synchronized(cache) {
                while (cache.size >= 220) {
                    val first = cache.keys.firstOrNull() ?: break
                    cache.remove(first)
                }
                cache[cacheKey] = CacheEntry(System.currentTimeMillis() + 5 * 60 * 1000, json)
            }
            return json
        }
    }

    // --- compactMovie (main.cjs: compactMovie/compactMovies) ---

    private fun mediaTypeOf(item: JSONObject, fallback: String): String {
        val mt = item.optString("media_type", "").lowercase()
        if (mt.isNotEmpty() && mt != "movie" && mt != "tv") return ""
        val t = if (mt.isNotEmpty()) mt else fallback.lowercase()
        return if (t == "tv") "tv" else "movie"
    }

    private fun compactMovie(movie: JSONObject, fallbackMediaType: String = "movie"): JSONObject? {
        if (!movie.has("id")) return null
        val mediaType = mediaTypeOf(movie, fallbackMediaType)
        if (mediaType.isEmpty()) return null
        return jsonOf(
            "id" to movie.get("id"),
            "mediaType" to mediaType,
            "title" to movie.optString("title").ifEmpty {
                movie.optString("name").ifEmpty { if (mediaType == "tv") "未命名节目" else "未命名电影" }
            },
            "originalTitle" to movie.optString("original_title").ifEmpty { movie.optString("original_name") },
            "overview" to movie.optString("overview"),
            "posterPath" to if (movie.isNull("poster_path")) null else movie.optString("poster_path"),
            "backdropPath" to if (movie.isNull("backdrop_path")) null else movie.optString("backdrop_path"),
            "voteAverage" to movie.optDouble("vote_average", 0.0),
            "voteCount" to movie.optInt("vote_count", 0),
            "releaseDate" to movie.optString("release_date").ifEmpty { movie.optString("first_air_date") },
            "genreIds" to (movie.optJSONArray("genre_ids") ?: JSONArray()),
            "popularity" to movie.optDouble("popularity", 0.0),
            "adult" to movie.optBoolean("adult", false)
        )
    }

    private fun compactMovies(results: JSONArray, limit: Int, fallbackMediaType: String): List<JSONObject> {
        val seen = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for (o in results.toObjectList()) {
            val m = compactMovie(o, fallbackMediaType) ?: continue
            val key = "${m.optString("mediaType")}:${m.opt("id")}"
            if (!seen.add(key)) continue
            if (m.optBoolean("adult")) continue
            out.add(m)
            if (out.size >= limit) break
        }
        return out
    }

    // --- initial (main.cjs: getInitialData) ---

    private suspend fun getGenres(): List<JSONObject> = coroutineScope {
        val movieD = async { runCatching { tmdbFetch("/genre/movie/list") }.getOrNull() }
        val tvD = async { runCatching { tmdbFetch("/genre/tv/list") }.getOrNull() }
        val byId = LinkedHashMap<Int, JSONObject>()
        movieD.await()?.optJSONArray("genres")?.toObjectList()?.forEach { byId[it.optInt("id")] = it }
        tvD.await()?.optJSONArray("genres")?.toObjectList()?.forEach {
            val id = it.optInt("id")
            if (!byId.containsKey(id)) byId[id] = it
        }
        byId.values.toList()
    }

    private suspend fun getTrending(limit: Int): List<JSONObject> {
        val data = tmdbFetch("/trending/all/day", mapOf("page" to "1"))
        return compactMovies(data.optJSONArray("results") ?: JSONArray(), limit, "movie")
    }

    private suspend fun getPopular(limit: Int): List<JSONObject> = coroutineScope {
        val movieD = async { runCatching { tmdbFetch("/movie/popular", mapOf("page" to "1", "region" to "CN")) }.getOrNull() }
        val tvD = async { runCatching { tmdbFetch("/tv/popular", mapOf("page" to "1")) }.getOrNull() }
        val merged = JSONArray()
        movieD.await()?.optJSONArray("results")?.toObjectList()?.forEach { merged.put(it) }
        tvD.await()?.optJSONArray("results")?.toObjectList()?.forEach {
            it.put("media_type", "tv")
            merged.put(it)
        }
        compactMovies(merged, limit, "movie")
    }

    private fun selectDailyMovie(movies: List<JSONObject>): JSONObject? {
        if (movies.isEmpty()) return null
        // dayNumber = floor(UTC midnight / 86400000), mirrors main.cjs selectDailyMovie
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val dayNumber = c.timeInMillis / 86400000L
        return movies[(dayNumber % movies.size).toInt()]
    }

    suspend fun getInitialData(credentialState: JSONObject): JSONObject = coroutineScope {
        val trendingD = async { withTimeoutOrNull(2600) { runCatching { getTrending(20) }.getOrNull() } }
        val genresD = async { withTimeoutOrNull(2600) { runCatching { getGenres() }.getOrNull() } }
        val popularD = async { withTimeoutOrNull(2600) { runCatching { getPopular(20) }.getOrNull() } }
        val trending = trendingD.await()
        val popular = popularD.await()
        if (trending == null && popular == null) {
            throw BridgeError("TMDB_NETWORK_FAILED", "首页数据加载失败，请检查网络或代理设置")
        }
        val genres = genresD.await() ?: emptyList()
        val base = if (!trending.isNullOrEmpty()) trending else popular!!
        jsonOf(
            "credential" to credentialState,
            "genres" to genres.toJsonArray(),
            "daily" to selectDailyMovie(base),
            "trending" to (trending ?: emptyList()).toJsonArray(),
            "popular" to (popular ?: emptyList()).toJsonArray()
        )
    }

    // --- search (main.cjs: tmdb:search handler) ---

    suspend fun searchMovies(query: String, page: Int, enrich: Boolean): JSONArray = coroutineScope {
        val movieD = async {
            runCatching {
                tmdbFetch("/search/movie", mapOf("query" to query, "page" to page.toString(), "region" to "CN", "include_adult" to "false"))
            }.getOrNull()
        }
        val tvD = async {
            runCatching {
                tmdbFetch("/search/tv", mapOf("query" to query, "page" to page.toString(), "include_adult" to "false"))
            }.getOrNull()
        }
        val merged = ArrayList<JSONObject>()
        movieD.await()?.optJSONArray("results")?.let { merged.addAll(compactMovies(it, 24, "movie")) }
        tvD.await()?.optJSONArray("results")?.let { merged.addAll(compactMovies(it, 24, "tv")) }

        if (enrich) {
            withTimeoutOrNull(1300) {
                coroutineScope {
                    // person enrich: top 2 people -> combined_credits (10 each)
                    val personD = async {
                        runCatching { tmdbFetch("/search/person", mapOf("query" to query, "page" to "1")) }.getOrNull()
                    }
                    val kwD = async {
                        runCatching { tmdbFetch("/search/keyword", mapOf("query" to query, "page" to "1")) }.getOrNull()
                    }
                    val persons = personD.await()?.optJSONArray("results")?.take(2) ?: emptyList()
                    val creditJobs = persons.mapNotNull { p ->
                        val id = p.optInt("id", 0)
                        if (id == 0) null else async {
                            runCatching { tmdbFetch("/person/$id/combined_credits") }.getOrNull()
                        }
                    }
                    for (job in creditJobs) {
                        job.await()?.optJSONArray("cast")?.let { merged.addAll(compactMovies(it, 10, "movie")) }
                    }
                    // keyword enrich -> discover movie|tv
                    val kwIds = kwD.await()?.optJSONArray("results")?.take(5)
                        ?.mapNotNull { it.optInt("id", 0).takeIf { id -> id != 0 } }
                        ?.joinToString(",").orEmpty()
                    if (kwIds.isNotEmpty()) {
                        val dmD = async {
                            runCatching {
                                tmdbFetch("/discover/movie", mapOf("with_keywords" to kwIds, "region" to "CN", "page" to "1"))
                            }.getOrNull()
                        }
                        val dtD = async {
                            runCatching {
                                tmdbFetch("/discover/tv", mapOf("with_keywords" to kwIds, "page" to "1"))
                            }.getOrNull()
                        }
                        dmD.await()?.optJSONArray("results")?.let { merged.addAll(compactMovies(it, 24, "movie")) }
                        dtD.await()?.optJSONArray("results")?.let { merged.addAll(compactMovies(it, 24, "tv")) }
                    }
                }
            }
        }

        val seen = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for (m in merged) {
            val key = "${m.optString("mediaType")}:${m.opt("id")}"
            if (!seen.add(key)) continue
            if (m.optBoolean("adult")) continue
            out.add(m)
            if (out.size >= 24) break
        }
        out.toJsonArray()
    }

    // --- discover (main.cjs: tmdb:discover handler) ---

    private val movieToTvGenreMap = mapOf(
        12 to listOf(10759), 14 to listOf(10765), 16 to listOf(16), 18 to listOf(18),
        27 to emptyList(), 28 to listOf(10759), 35 to listOf(35), 36 to emptyList(),
        37 to listOf(37), 53 to listOf(9648, 10759), 80 to listOf(80), 99 to listOf(99),
        878 to listOf(10765), 9648 to listOf(9648), 10402 to emptyList(), 10749 to listOf(18),
        10751 to listOf(10751), 10752 to listOf(10768), 10770 to emptyList()
    )

    private fun mapGenreParamToTv(value: String): String? {
        if (value.isBlank()) return null
        val sep = if ('|' in value) "|" else ","
        val mapped = value.split(',', '|')
            .mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 }
            .flatMap { movieToTvGenreMap[it] ?: listOf(it) }
            .filter { it != 0 }.distinct()
        return mapped.joinToString(sep).ifEmpty { null }
    }

    suspend fun discoverMovies(options: JSONObject): JSONArray = coroutineScope {
        val mediaType = options.optString("mediaType", "all")
        val page = options.optInt("page", 1).coerceAtLeast(1)
        val sortBy = options.optString("sortBy", "popularity.desc").ifBlank { "popularity.desc" }
        val voteCountGte = options.optDouble("minVotes", 80.0)
        val voteAverageGte = if (options.has("minRating")) options.optDouble("minRating").toString() else null
        val withoutGenres = options.optJSONArray("withoutGenreIds")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optInt(it, 0).takeIf { id -> id != 0 } }.joinToString(",")
        }
        val genreRaw = when {
            options.has("genreIds") -> options.optJSONArray("genreIds")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optInt(it, 0).takeIf { id -> id != 0 } }.joinToString(",")
            } ?: ""
            options.has("genreId") -> options.optInt("genreId", 0).takeIf { it != 0 }?.toString() ?: ""
            else -> ""
        }
        val genreMode = options.optString("genreMode", "and")
        val withGenres = if (genreRaw.isNotBlank() && genreMode == "or") genreRaw.replace(",", "|") else genreRaw

        val merged = ArrayList<JSONObject>()

        if (mediaType == "all" || mediaType == "movie") {
            val params = HashMap<String, String>()
            params["sort_by"] = sortBy
            params["vote_count.gte"] = voteCountGte.toInt().toString()
            if (voteAverageGte != null) params["vote_average.gte"] = voteAverageGte
            if (withGenres.isNotBlank()) params["with_genres"] = withGenres
            if (!withoutGenres.isNullOrBlank()) params["without_genres"] = withoutGenres
            params["region"] = "CN"
            params["page"] = page.toString()
            runCatching { tmdbFetch("/discover/movie", params) }.getOrNull()
                ?.optJSONArray("results")?.let { merged.addAll(compactMovies(it, 24, "movie")) }
        }
        val tvGenres = if (genreRaw.isNotBlank()) mapGenreParamToTv(withGenres) else null
        if (mediaType == "tv" || (mediaType == "all" && tvGenres != null)) {
            val params = HashMap<String, String>()
            params["sort_by"] = sortBy
            params["vote_count.gte"] = voteCountGte.toInt().toString()
            if (voteAverageGte != null) params["vote_average.gte"] = voteAverageGte
            if (tvGenres != null) params["with_genres"] = tvGenres
            if (!withoutGenres.isNullOrBlank()) params["without_genres"] = withoutGenres
            params["page"] = page.toString()
            runCatching { tmdbFetch("/discover/tv", params) }.getOrNull()
                ?.optJSONArray("results")?.let { merged.addAll(compactMovies(it, 24, "tv")) }
        }

        val seen = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for (m in merged) {
            val key = "${m.optString("mediaType")}:${m.opt("id")}"
            if (!seen.add(key)) continue
            if (m.optBoolean("adult")) continue
            out.add(m)
            if (out.size >= 24) break
        }
        out.toJsonArray()
    }

    // --- recommendations / details ---

    suspend fun recommendByMovie(movieId: Long, mediaType: String, page: Int): JSONArray {
        val mt = if (mediaType == "tv") "tv" else "movie"
        val data = runCatching {
            tmdbFetch("/$mt/$movieId/recommendations", mapOf("page" to page.coerceAtLeast(1).toString()))
        }.getOrNull() ?: return JSONArray()
        return compactMovies(data.optJSONArray("results") ?: JSONArray(), 20, mt).toJsonArray()
    }

    suspend fun getMovieDetails(movieId: Long, mediaType: String): JSONObject {
        val mt = if (mediaType == "tv") "tv" else "movie"
        val data = tmdbFetch(
            "/$mt/$movieId",
            mapOf("append_to_response" to "credits,aggregate_credits,recommendations,similar,videos")
        )
        val credits = data.optJSONObject("credits")
        val aggCredits = data.optJSONObject("aggregate_credits")
        val castSrc = credits?.optJSONArray("cast") ?: aggCredits?.optJSONArray("cast") ?: JSONArray()
        val crewSrc = credits?.optJSONArray("crew") ?: aggCredits?.optJSONArray("crew") ?: JSONArray()

        val cast = JSONArray()
        for (c in castSrc.take(8)) {
            if (c.optString("name").isBlank()) continue
            val character = c.optString("character").ifEmpty {
                c.optJSONArray("roles")?.optJSONObject(0)?.optString("character").orEmpty()
            }
            cast.put(jsonOf(
                "id" to c.opt("id"),
                "name" to c.optString("name"),
                "character" to character,
                "profilePath" to if (c.isNull("profile_path")) null else c.optString("profile_path")
            ))
        }
        val wantedJobs = setOf("Director", "Writer", "Screenplay", "Creator", "Executive Producer")
        val crew = JSONArray()
        for (c in crewSrc.toObjectList()) {
            if (crew.length() >= 6) break
            val job = c.optString("job").ifEmpty {
                c.optJSONArray("jobs")?.optJSONObject(0)?.optString("job").orEmpty()
            }
            val jobs = c.optJSONArray("jobs")?.let { arr ->
                (0 until arr.length()).map { arr.optJSONObject(it)?.optString("job").orEmpty() }
            } ?: emptyList()
            if (c.optString("name").isBlank()) continue
            if (job !in wantedJobs && jobs.none { it in wantedJobs }) continue
            crew.put(jsonOf(
                "id" to c.opt("id"),
                "name" to c.optString("name"),
                "job" to job,
                "profilePath" to if (c.isNull("profile_path")) null else c.optString("profile_path")
            ))
        }
        val videos = JSONArray()
        data.optJSONObject("videos")?.optJSONArray("results")?.toObjectList()
            ?.filter { it.optString("site") == "YouTube" && it.optString("key").isNotEmpty() }
            ?.take(4)?.forEach {
                videos.put(jsonOf(
                    "id" to it.opt("id"), "key" to it.optString("key"),
                    "name" to it.optString("name"), "type" to it.optString("type")
                ))
            }

        fun personList(key: String): JSONArray {
            val arr = JSONArray()
            data.optJSONObject(key)?.optJSONArray("results")?.let {
                arr.putAll(compactMovies(it, 10, mt).toJsonArray())
            }
            return arr
        }

        val genres = JSONArray()
        data.optJSONArray("genres")?.toObjectList()?.forEach {
            genres.put(jsonOf("id" to it.optInt("id"), "name" to it.optString("name")))
        }
        val countries = JSONArray()
        data.optJSONArray("production_countries")?.toObjectList()?.forEach {
            countries.put(jsonOf("iso_3166_1" to it.optString("iso_3166_1"), "name" to it.optString("name")))
        }
        val languages = JSONArray()
        data.optJSONArray("spoken_languages")?.toObjectList()?.forEach {
            languages.put(jsonOf("iso_639_1" to it.optString("iso_639_1"), "name" to it.optString("name")))
        }

        return jsonOf(
            "id" to data.opt("id"),
            "mediaType" to mt,
            "title" to data.optString("title").ifEmpty { data.optString("name") },
            "originalTitle" to data.optString("original_title").ifEmpty { data.optString("original_name") },
            "tagline" to data.optString("tagline"),
            "overview" to data.optString("overview"),
            "posterPath" to if (data.isNull("poster_path")) null else data.optString("poster_path"),
            "backdropPath" to if (data.isNull("backdrop_path")) null else data.optString("backdrop_path"),
            "releaseDate" to data.optString("release_date").ifEmpty { data.optString("first_air_date") },
            "runtime" to if (data.isNull("runtime")) null else data.optInt("runtime"),
            "numberOfSeasons" to if (data.isNull("number_of_seasons")) null else data.optInt("number_of_seasons"),
            "numberOfEpisodes" to if (data.isNull("number_of_episodes")) null else data.optInt("number_of_episodes"),
            "voteAverage" to data.optDouble("vote_average", 0.0),
            "voteCount" to data.optInt("vote_count", 0),
            "popularity" to data.optDouble("popularity", 0.0),
            "status" to data.optString("status"),
            "homepage" to data.optString("homepage"),
            "genres" to genres,
            "productionCountries" to countries,
            "spokenLanguages" to languages,
            "cast" to cast,
            "crew" to crew,
            "recommendations" to personList("recommendations"),
            "similar" to personList("similar"),
            "videos" to videos
        )
    }

    private fun JSONArray.putAll(other: JSONArray) {
        for (i in 0 until other.length()) put(other.get(i))
    }
}
