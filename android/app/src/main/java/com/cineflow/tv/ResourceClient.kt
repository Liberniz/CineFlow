package com.cineflow.tv

import com.cineflow.tv.Net.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
* Resource-source client. Ports the resource subsystem of electron/main.cjs:
* source merging, import parsing (incl. TVBox format), candidate scoring,
* play-group parsing, detail hydration, and media URL resolving.
*
* Not ported: the local HTTP media proxy (getMediaProxyUrl is identity on TV;
* ExoPlayer handles HLS/DASH/progressive directly).
*/
class ResourceClient(private val store: Store) {

data class Source(
val key: String,
val name: String,
val api: String,
val modes: List<String>,
val enabled: Boolean,
val origin: String, // "builtin" | "custom"
val note: String
)

companion object {
val BUILTINS = listOf(
Source("builtin-dyttzy", "电影天堂", "http://caiji.dyttzyapi.com/api.php/provide/vod", listOf("stable", "movie", "full"), true, "builtin", ""),
Source("builtin-ffzy", "非凡资源", "http://ffzy5.tv/api.php/provide/vod", listOf("stable", "movie", "full"), true, "builtin", ""),
Source("builtin-zy360", "360资源", "https://360zy.com/api.php/provide/vod", listOf("stable", "movie", "full"), true, "builtin", ""),
Source("builtin-zuid", "最大资源", "https://api.zuidapi.com/api.php/provide/vod", listOf("movie", "full"), true, "builtin", ""),
Source("builtin-wujin", "无尽资源", "https://api.wujinapi.me/api.php/provide/vod", listOf("stable", "movie", "full"), true, "builtin", ""),
Source("builtin-bfzy", "暴风资源", "https://bfzyapi.com/api.php/provide/vod", listOf("full"), true, "builtin", ""),
Source("builtin-lzi", "量子资源", "https://cj.lziapi.com/api.php/provide/vod", listOf("full"), true, "builtin", ""),
Source("builtin-ruyi", "如意资源", "https://cj.rycjapi.com/api.php/provide/vod", listOf("stable", "movie", "full"), true, "builtin", "")
)

val RESOURCE_MODES = listOf(
Triple("stable", "稳定优先", "优先查询响应较快、结果较干净的资源源。"),
Triple("movie", "电影片库优先", "偏向电影片库，减少综艺/解说干扰。"),
Triple("full", "全部来源", "轮询更多来源，发现率更高但耗时稍长。"),
Triple("off", "关闭资源查找", "介绍页底部不自动查找播放资源。")
)
val PLAYBACK_MODES = listOf(
Triple("auto", "自动判断（推荐）", "直链资源直接播放，HLS / DASH / FLV 仅在必要时使用本地代理。"),
Triple("direct", "直连优先", "尽量跳过本地媒体代理，适合支持 CORS 的资源。"),
Triple("proxy", "本地代理优先", "始终通过本地媒体代理播放，兼容性最好。")
)

private val PLAYABLE_RE = Regex("\\.(?:m3u8|mpd|mp4|m4v|webm|ogv|ogg|flv|ts|m2ts|mts|mov|mkv|avi|mpeg|mpg|3gp|f4v)(?:$|[?#])", RegexOption.IGNORE_CASE)
private val PAGE_HINT_RE = Regex("/(?:share|play|player|embed|vodplay|video)/", RegexOption.IGNORE_CASE)
}

// ------------------------------------------------------------------
// source list management
// ------------------------------------------------------------------

private fun normalizeApi(raw: String): String {
var value = raw.trim()
if (value.isEmpty()) return ""
if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(value) &&
Regex("^[\\w.-]+(?::\\d+)?(?:[/?#]|\$)").containsMatchIn(value)
) {
value = "https://$value"
}
return try {
val url = value.toHttpUrl()
if (url.scheme!= "http" && url.scheme!= "https") "" else url.toString()
} catch (e: Exception) {
""
}
}

private fun normalizeModes(raw: Any?): List<String> {
val valid = setOf("stable", "movie", "full")
val list: List<String> = when (raw) {
is String -> raw.split(Regex("[,|\\s]+")).map { it.trim().lowercase()}
is JSONArray -> (0 until raw.length()).map { raw.optString(it).trim().lowercase()}
is Collection<*> -> raw.map { it.toString().trim().lowercase()}
else -> emptyList()
}
val filtered = list.filter { it in valid}.distinct()
return if (filtered.isEmpty()) listOf("stable", "movie", "full") else filtered
}

private fun sourceKey(name: String, api: String, origin: String, index: Int): String {
val digest = MessageDigest.getInstance("SHA-1").digest("$origin|$api".toByteArray())
val hex = digest.joinToString("") { "%02x".format(it)}.take(10)
return "$origin-${name.ifBlank { "src"}}-$hex-$index".replace(Regex("[^a-zA-Z0-9_-]"), "")
}

private fun normalizeEntry(entry: JSONObject, index: Int, origin: String): Source? {
if (entry.length() == 0) return null
val api = normalizeApi(entry.optString("api").ifEmpty { entry.optString("url")}.ifEmpty { entry.optString("endpoint")})
if (api.isEmpty()) return null
val rawName = entry.optString("name").ifEmpty { entry.optString("label")}.ifEmpty { entry.optString("title")}
val name = rawName.trim().ifEmpty {
try { api.toHttpUrl().host} catch (e: Exception) { "自定义源${index + 1}"}
}
val key = entry.optString("key").ifEmpty { entry.optString("id")}.trim()
.ifEmpty { sourceKey(name, api, origin, index)}
val enabled = when {
entry.has("enabled") -> entry.optBoolean("enabled", true)
entry.has("disabled") ->!entry.optBoolean("disabled", false)
else -> true
}
return Source(
key = key,
name = name,
api = api,
modes = normalizeModes(if (entry.has("modes")) entry.get("modes") else if (entry.has("mode")) entry.get("mode") else null),
enabled = enabled,
origin = origin,
note = entry.optString("note").trim()
)
}

fun getCustomSources(): List<Source> {
val arr = try {
JSONArray(store.customResourceSources)
} catch (e: Exception) {
return emptyList()
}
return arr.toObjectList().mapIndexedNotNull { i, o -> normalizeEntry(o, i, "custom")}
}

fun getMergedSources(): List<Source> {
val merged = ArrayList<Source>()
val seen = HashSet<String>()
fun push(s: Source) {
val k = "${s.key}|${s.api}"
if (k in seen || s.api in seen) return
seen.add(k); seen.add(s.api)
merged.add(s)
}
getCustomSources().forEach { push(it)}
BUILTINS.forEach { push(it)}
return merged
}

fun selectSources(mode: String): List<Source> {
if (mode == "off") return emptyList()
return getMergedSources().filter { it.enabled && mode in it.modes}
}

// ------------------------------------------------------------------
// public settings shape (mirrors publicResourceSettings)
// ------------------------------------------------------------------

fun publicResourceSettings(): JSONObject {
val mode = store.resourceMode.takeIf { it in setOf("stable", "movie", "full", "off")}?: "stable"
val playbackMode = store.resourcePlaybackMode.takeIf { it in setOf("auto", "direct", "proxy")}?: "auto"
val merged = getMergedSources()
val customs = merged.count { it.origin == "custom"}
val modeLabel = RESOURCE_MODES.firstOrNull { it.first == mode}
val pbLabel = PLAYBACK_MODES.firstOrNull { it.first == playbackMode}
return jsonOf(
"mode" to mode,
"label" to (modeLabel?.second?: mode),
"description" to (modeLabel?.third?: ""),
"enabled" to (mode!= "off"),
"revision" to store.resourceConfigRevision,
"playbackMode" to playbackMode,
"playbackLabel" to (pbLabel?.second?: playbackMode),
"playbackDescription" to (pbLabel?.third?: ""),
"sourceCount" to merged.size,
"builtInSourceCount" to BUILTINS.size,
"customSourceCount" to customs,
"sources" to JSONArray(merged.map { s ->
jsonOf(
"key" to s.key, "name" to s.name, "api" to s.api,
"modes" to JSONArray(s.modes), "enabled" to s.enabled,
"origin" to s.origin, "note" to s.note
)
}),
"modes" to JSONArray(RESOURCE_MODES.map { (v, l, d) -> jsonOf("value" to v, "label" to l, "description" to d)}),
"playbackModes" to JSONArray(PLAYBACK_MODES.map { (v, l, d) -> jsonOf("value" to v, "label" to l, "description" to d)})
)
}

// ------------------------------------------------------------------
// import parsing (mirrors normalizeResourceSourcesPayload + TVBox support)
// ------------------------------------------------------------------

private fun isTvBoxSite(o: JSONObject): Boolean {
if (o.optString("api").trim().isEmpty()) return false
return o.has("type")
}

private fun tvBoxToEntry(site: JSONObject): JSONObject? {
val api = site.optString("api").trim()
if (!api.startsWith("http://", true) &&!api.startsWith("https://", true)) return null
if (api.startsWith("csp_", true)) return null
// type=1 only; missing type passes through as a plain source
val hasType = site.has("type") && site.optString("type").trim().isNotEmpty()
if (hasType && site.optString("type").trim()!= "1") return null
return jsonOf("name" to site.optString("name").ifEmpty { site.optString("key")}, "api" to api)
}

private fun extractTvBoxSites(raw: Any?): List<JSONObject>? {
val sites: JSONArray? = when (raw) {
is JSONArray -> raw
is JSONObject -> raw.optJSONArray("sites")
else -> null
}?: return null
val list = sites.toObjectList()
if (list.isEmpty() || list.none { isTvBoxSite(it)}) return null
val entries = list.mapNotNull { tvBoxToEntry(it)}
if (entries.isEmpty()) {
throw BridgeError(
"INVALID_RESOURCE_SOURCE_CONFIG",
"TVBox 配置中没有可用的 type=1 JSON 接口（spider/jar 及 xml 源暂不支持）。"
)
}
return entries
}

private fun normalizePayload(raw: Any?): List<JSONObject> {
if (raw is JSONObject || raw is JSONArray) {
extractTvBoxSites(raw)?.let { return it}
if (raw is JSONObject) {
raw.optJSONArray("sources")?.let { return it.toObjectList()}
if (raw.optString("name").isNotEmpty() || raw.optString("api").isNotEmpty() ||
raw.optString("url").isNotEmpty() || raw.optString("endpoint").isNotEmpty()
) return listOf(raw)
}
if (raw is JSONArray) return raw.toObjectList()
}
val text = raw?.toString()?.replace("\uFEFF", "")?.trim().orEmpty()
if (text.isEmpty()) return emptyList()
return text.split(Regex("\r?\n"))
.map { it.trim()}
.filter { it.isNotEmpty() &&!it.startsWith("#") &&!it.startsWith("//")}
.mapNotNull { line ->
if (line.startsWith("{") || line.startsWith("[")) {
try {
val parsed = JSONObject(line)
if (isTvBoxSite(parsed)) tvBoxToEntry(parsed) else parsed
} catch (e: Exception) {
null
}
} else {
val parts = when {
'|' in line -> line.split('|')
'\t' in line -> line.split('\t')
else -> line.split(Regex("\\s{2,}"))
}
if (parts.isEmpty()) return@mapNotNull null
if (parts.size == 1) {
val api = normalizeApi(parts[0])
if (api.isEmpty()) null else jsonOf("api" to api)
} else {
val name = parts[0].trim()
val apiRaw = parts.getOrNull(1)?.trim().orEmpty()
val api = normalizeApi(apiRaw.ifEmpty { name})
if (api.isEmpty()) return@mapNotNull null
jsonOf(
"name" to if (apiRaw.isNotEmpty()) name else "",
"api" to api,
"modes" to (parts.getOrNull(2)?.trim().orEmpty()),
"note" to (parts.getOrNull(3)?.trim().orEmpty())
)
}
}
}
}

private suspend fun fetchRemoteText(url: String): String {
val req = Request.Builder().url(url)
.header("Accept", "application/json, text/plain, */*")
.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 CineFlow/3.2.0")
.build()
val client = Net.client(store.proxy).newBuilder()
.callTimeout(10, TimeUnit.SECONDS).build()
val resp = try {
client.newCall(req).await()
} catch (e: IOException) {
throw BridgeError("RESOURCE_SOURCE_HTTP_FAILED", "外部源拉取失败：${e.message}")
}
resp.use {
val text = it.body?.string().orEmpty()
if (!it.isSuccessful) throw BridgeError("RESOURCE_SOURCE_HTTP_FAILED", "外部源拉取失败：${it.code}")
return text
}
}

suspend fun importResourceSources(payload: Any?): JSONObject {
var input: Any? = payload
val text = payload?.toString()?.replace("\uFEFF", "")?.trim().orEmpty()
if (payload == null || payload is String) {
if (text.isEmpty()) throw BridgeError("INVALID_RESOURCE_SOURCE_CONFIG", "请输入外部源 JSON、分行配置或源配置地址。")
input = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text) && '\n'!in text) {
fetchRemoteText(text)
} else text
}
val entries: List<JSONObject> = if (input is String) {
val t = input.replace("\uFEFF", "").trim()
if (t.isEmpty()) emptyList()
else try {
normalizePayload(JSONObject(t))
} catch (e: BridgeError) {
throw e
} catch (e: Exception) {
try {
normalizePayload(JSONArray(t))
} catch (e2: Exception) {
normalizePayload(t)
}
}
} else {
normalizePayload(input)
}

val normalized = entries.mapIndexedNotNull { i, e -> normalizeEntry(e, i, "custom")}
val unique = ArrayList<Source>()
val seenApis = HashSet<String>()
for (s in normalized) {
if (s.api.isEmpty() ||!seenApis.add(s.api)) continue
unique.add(s)
}
if (unique.isEmpty()) {
throw BridgeError("INVALID_RESOURCE_SOURCE_CONFIG", "未识别到有效外部源，请检查格式后重试。")
}
val arr = JSONArray()
unique.forEachIndexed { i, s ->
arr.put(jsonOf(
"key" to s.key, "name" to s.name, "api" to s.api,
"modes" to JSONArray(s.modes), "enabled" to s.enabled,
"origin" to s.origin, "note" to s.note,
"importedAt" to System.currentTimeMillis()
))
}
store.customResourceSources = arr.toString()
store.touchResourceConfig()
return publicResourceSettings()
}

fun clearResourceSources(): JSONObject {
store.customResourceSources = "[]"
store.touchResourceConfig()
return publicResourceSettings()
}

// ------------------------------------------------------------------
// fetching
// ------------------------------------------------------------------

private val resourceCache = LinkedHashMap<String, Pair<Long, JSONObject>>()
private val resolveCache = LinkedHashMap<String, Pair<Long, JSONObject>>()

private fun cacheGet(map: LinkedHashMap<String, Pair<Long, JSONObject>>, key: String): JSONObject? {
synchronized(map) {
val (exp, v) = map[key]?: return null
if (System.currentTimeMillis() > exp) {
map.remove(key); return null
}
return v
}
}

private fun cachePut(map: LinkedHashMap<String, Pair<Long, JSONObject>>, key: String, value: JSONObject, ttlMs: Long, max: Int) {
synchronized(map) {
while (map.size >= max) {
val first = map.keys.firstOrNull()?: break
map.remove(first)
}
map[key] = System.currentTimeMillis() + ttlMs to value
}
}

private suspend fun resourceFetchJson(source: Source, params: Map<String, String>): JSONObject {
val urlBuilder = source.api.toHttpUrl().newBuilder()
for ((k, v) in params) urlBuilder.addQueryParameter(k, v)
val cacheKey = urlBuilder.build().toString()
cacheGet(resourceCache, cacheKey)?.let { return it}
val req = Request.Builder().url(urlBuilder.build())
.header("Accept", "application/json, text/plain, */*")
.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 CineFlow/3.2.0")
.build()
val client = Net.client(store.proxy).newBuilder()
.callTimeout(10, TimeUnit.SECONDS).build()
val resp = try {
withTimeoutOrNull(6500) { client.newCall(req).await()}
?: throw IOException("timeout")
} catch (e: Exception) {
throw IOException("resource fetch failed: ${e.message}")
}
resp.use {
val text = it.body?.string().orEmpty()
if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
val json = try {
JSONObject(text)
} catch (e: Exception) {
throw IOException("bad payload")
}
if (json.optJSONArray("list") == null) throw IOException("bad payload")
cachePut(resourceCache, cacheKey, json, 10 * 60 * 1000, 420)
return json
}
}

// ------------------------------------------------------------------
// scoring (mirrors resourceCandidateScore / normalizeResourceTitle)
// ------------------------------------------------------------------

private fun normalizeResourceTitle(value: String): String {
return value.lowercase()
.replace("&amp;", "&")
.replace(Regex("\\[[^\\]]*?(解说|预告|花絮|短评)[^\\]]*?\\]"), "")
.replace(Regex("]*?(解说|预告|花絮|短评)[^】]*?】"), "")
.replace(Regex("\\([^)]*?(解说|预告|花絮|短评|原声版|普通话|国语|粤语|英语|中字|字幕)[^)]*?\\)"), "")
.replace(Regex("（[^）]*?(解说|预告|花絮|短评|原声版|普通话|国语|粤语|英语|中字|字幕)[^）]*?）"), "")
.replace(Regex("第[一二三四五六七八九十0-9]+季"), "")
.replace(Regex("[\\s·・:：,，.。!！?？'\"“”‘’《》〈〉\\-—_/\\\\|]+"), "")
.trim()
}

private fun resourceCandidateScore(item: JSONObject, titles: List<String>, releaseYear: String): Int {
val rawName = item.optString("vod_name").ifEmpty { item.optString("name")}
val name = normalizeResourceTitle(rawName)
if (name.isEmpty()) return -999
var best = -999
for (title in titles) {
val target = normalizeResourceTitle(title)
if (target.isEmpty()) continue
var score = -999
when {
name == target -> score = 110
name.startsWith(target) -> {
val suffix = name.substring(target.length)
score = when {
Regex("^(19|20)\\d{2}\$").matches(suffix) -> 70
Regex("^[0-9一二三四五六七八九十]").containsMatchIn(suffix) &&
!Regex("[0-9一二三四五六七八九十]\$").containsMatchIn(target) -> 22
else -> 72 - minOf(18, suffix.length * 2)
}
}
target.startsWith(name) && target.length - name.length <= 4 -> score = 58
name.contains(target) && target.length >= 4 ->
score = 52 - minOf(12, name.length - target.length)
}
best = maxOf(best, score)
}
val haystack = "$rawName ${item.optString("type_name")} ${item.optString("vod_remarks")}"
if (Regex("解说|预告|花絮|短评").containsMatchIn(haystack)) best -= 80
if (Regex("体育|NBA|CBA|WTA|世界杯|比赛").containsMatchIn(haystack)) best -= 36
val itemYear = Regex("\\d{4}").find(item.optString("vod_year").ifEmpty { item.optString("year")})?.value.orEmpty()
if (releaseYear.isNotEmpty() && itemYear.isNotEmpty()) best += if (itemYear == releaseYear) 12 else -8
return best
}

private fun uniqueResourceQueries(payload: JSONObject): List<String> {
val title = payload.optString("title")
val values = listOf(
title,
payload.optString("originalTitle"),
title.replace(Regex("[：:].*\$"), ""),
title.replace(Regex("[（(].*?[）)]"), "")
)
val seen = HashSet<String>()
return values.map { it.trim()}
.filter { it.length >= 2}
.filter { seen.add(it.lowercase())}
.take(4)
}

private fun releaseYearOf(payload: JSONObject): String {
val raw = payload.optString("year")
.ifEmpty { payload.optString("releaseYear")}
.ifEmpty { payload.optString("releaseDate")}
return Regex("\\d{4}").find(raw)?.value.orEmpty()
}

// ------------------------------------------------------------------
// play groups (mirrors parsePlayGroups / playbackMetaForUrl)
// ------------------------------------------------------------------

fun inferMediaKind(value: String, contentType: String = ""): String {
val text = value.lowercase()
val type = contentType.lowercase()
if ("mpegurl" in type || "x-mpegurl" in type || "vnd.apple.mpegurl" in type ||
Regex("\\.m3u8(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text) || "m3u8" in text
) return "hls"
if ("dash+xml" in type || "mpeg-dash" in type ||
Regex("\\.mpd(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)
) return "dash"
if ("x-flv" in type || "flv" in type ||
Regex("\\.flv(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)
) return "flv"
if ("mp2t" in type || "mpegts" in type ||
Regex("\\.(?:ts|m2ts|mts)(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)
) return "mpegts"
if ("mp4" in type ||
Regex("\\.m4v(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
Regex("\\.mp4(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)
) return "mp4"
if ("webm" in type || Regex("\\.webm(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "webm"
if ("ogg" in type || Regex("\\.(?:ogv|ogg)(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "ogg"
if (Regex("\\.(?:mp4|m4v|webm|ogv|ogg|mov|mkv|avi|mpeg|mpg|3gp|f4v|m3u8|mpd|flv|ts|m2ts|mts)(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
type.startsWith("video/") || type.startsWith("audio/")
) return "native"
if (PAGE_HINT_RE.containsMatchIn(text)) return "page"
return "native"
}

fun mediaFormatLabel(kind: String): String = when (kind) {
"hls" -> "HLS"
"dash" -> "DASH"
"flv" -> "FLV"
"mpegts" -> "MPEG-TS"
"mp4" -> "MP4"
"webm" -> "WebM"
"ogg" -> "OGG"
"page" -> "网页解析"
else -> "直连"
}

private fun playbackMetaForUrl(url: String, lineName: String): Triple<String, String, Boolean> {
val kind = inferMediaKind(url)
val needsResolve = kind == "page" || (!PLAYABLE_RE.containsMatchIn(url) && PAGE_HINT_RE.containsMatchIn(url))
var format = mediaFormatLabel(kind)
if (needsResolve) {
format = if ("m3u8" in lineName.lowercase()) "网页解析·HLS" else "网页解析"
}
return Triple(kind, format, needsResolve)
}

private fun parsePlayGroups(item: JSONObject): List<Pair<String, List<JSONObject>>> {
val fromGroups = item.optString("vod_play_from").ifEmpty { item.optString("play_from")}.ifEmpty { "默认线路"}.split("\$\$\$")
val urlGroups = item.optString("vod_play_url").ifEmpty { item.optString("play_url")}.split("\$\$\$")
val out = ArrayList<Pair<String, List<JSONObject>>>()
for ((index, group) in urlGroups.withIndex()) {
val episodes = ArrayList<JSONObject>()
for ((epIndex, segment) in group.split("#").withIndex()) {
val clean = segment.trim()
if (clean.isEmpty()) continue
val dollar = clean.lastIndexOf('$')
val label = (if (dollar > 0) clean.substring(0, dollar) else "播放 ${epIndex + 1}").trim().ifEmpty { "播放 ${epIndex + 1}"}
val url = if (dollar > 0) clean.substring(dollar + 1) else clean
if (!url.startsWith("http://", true) &&!url.startsWith("https://", true)) continue
val (kind, format, needsResolve) = playbackMetaForUrl(url, fromGroups.getOrNull(index)?: fromGroups[0])
episodes.add(jsonOf("label" to label, "url" to url, "kind" to kind, "format" to format, "needsResolve" to needsResolve))
if (episodes.size >= 240) break
}
if (episodes.isEmpty()) continue
val name = (fromGroups.getOrNull(index)?: fromGroups[0]).trim().ifEmpty { "线路 ${index + 1}"}
out.add(name to episodes)
if (out.size >= 4) break
}
return out
}

private fun compactResourceItem(source: Source, item: JSONObject, payload: JSONObject): JSONObject? {
val groups = parsePlayGroups(item)
if (groups.isEmpty()) return null
val first = groups[0].second.firstOrNull()
val seasonEpisodeCount = groups.maxOf { it.second.size}
val formats = groups.flatMap { (_, eps) -> eps.map { it.optString("format")}}
.filter { it.isNotEmpty()}.distinct().take(5)
val title = item.optString("vod_name").ifEmpty { payload.optString("title")}.ifEmpty { "未命名资源"}
val posterRaw = item.optString("vod_pic")
return jsonOf(
"sourceKey" to source.key,
"sourceName" to source.name,
"title" to title,
"year" to item.optString("vod_year"),
"type" to item.optString("type_name"),
"remarks" to item.optString("vod_remarks").ifEmpty { item.optString("vod_pubdate")},
"updatedAt" to item.optString("vod_time").ifEmpty { item.optString("vod_time_add")},
"poster" to if (posterRaw.startsWith("http://", true) || posterRaw.startsWith("https://", true)) posterRaw else "",
"firstUrl" to (first?.optString("url").orEmpty()),
"firstLabel" to (first?.optString("label")?: "播放"),
"formats" to JSONArray(formats),
"isSeries" to (seasonEpisodeCount > 1),
"seasonEpisodeCount" to seasonEpisodeCount,
"playGroupCount" to groups.size,
"episodeCount" to groups.sumOf { it.second.size},
"lines" to JSONArray(groups.map { (name, eps) ->
jsonOf("name" to name, "episodes" to JSONArray(eps))
})
)
}

private suspend fun hydrateResourceDetail(source: Source, item: JSONObject): JSONObject {
if (parsePlayGroups(item).isNotEmpty()) return item
val vodId = item.optString("vod_id").ifEmpty { item.opt("vod_id")?.toString().orEmpty()}
if (vodId.isEmpty()) return item
for (ac in listOf("detail", "videolist")) {
try {
val detail = resourceFetchJson(source, mapOf("ac" to ac, "ids" to vodId))
val first = detail.optJSONArray("list")?.optJSONObject(0)?: continue
val merged = JSONObject(item.toString())
val keys = first.keys()
while (keys.hasNext()) {
val k = keys.next()
merged.put(k, first.get(k))
}
return merged
} catch (e: Exception) {
// try the other probe
}
}
return item
}

private suspend fun searchResourceSource(source: Source, payload: JSONObject): JSONObject? {
val titles = uniqueResourceQueries(payload)
val releaseYear = releaseYearOf(payload)
for (query in titles) {
val data = try {
resourceFetchJson(source, mapOf("ac" to "videolist", "wd" to query))
} catch (e: Exception) {
continue
}
val list = data.optJSONArray("list")?: continue
val candidates = list.toObjectList()
.map { it to resourceCandidateScore(it, titles, releaseYear)}
.filter { it.second >= 44}
.sortedByDescending { it.second}
.take(4)
for ((item, _) in candidates) {
val hydrated = try {
hydrateResourceDetail(source, item)
} catch (e: Exception) {
item
}
val compact = compactResourceItem(source, hydrated, payload)
if (compact!= null) return compact
}
}
return null
}

suspend fun findMovieResources(payload: JSONObject, cursor: Int, limit: Int): JSONObject = coroutineScope {
val settings = publicResourceSettings()
val all = selectSources(store.resourceMode)
if (!settings.optBoolean("enabled") || all.isEmpty()) {
return@coroutineScope settings
.put("checked", 0).put("total", 0).put("nextCursor", 0)
.put("done", true).put("resources", JSONArray())
}
val c = cursor.coerceAtLeast(0)
val l = limit.coerceIn(1, 4)
val batch = all.subList(c, minOf(c + l, all.size))
val results = batch.map { async { runCatching { searchResourceSource(it, payload)}.getOrNull()}}
.awaitAll().filterNotNull()
val nextCursor = minOf(all.size, c + batch.size)
settings
.put("checked", nextCursor).put("total", all.size)
.put("nextCursor", nextCursor).put("done", nextCursor >= all.size)
.put("resources", JSONArray(results))
}

// ------------------------------------------------------------------
// media URL resolving (mirrors resolveMediaUrl / extractMediaUrlsFromHtml)
// ------------------------------------------------------------------

private fun decodeMediaCandidate(value: String): String {
var text = value.trim()
if (text.isEmpty()) return ""
text = text.replace("&amp;", "&", true).replace("&quot;", "\"", true)
.replace("&#39;", "'", true).replace("&lt;", "<", true).replace("&gt;", ">", true)
// unescape \/ and \uXXXX (mirrors the JSON.parse trick in main.cjs)
text = text.replace("\\/", "/")
text = Regex("\\\\u([0-9a-fA-F]{4})").replace(text) {
it.groupValues[1].toInt(16).toChar().toString()
}
return text.trim()
}

private fun normalizeExtractedMediaUrl(candidate: String, baseUrl: String): String {
val decoded = decodeMediaCandidate(candidate)
if (decoded.isEmpty()) return ""
if (decoded.startsWith("javascript:", true) || decoded.startsWith("data:", true) || decoded.startsWith("blob:", true)) return ""
if (Regex("\\.(?:jpg|jpeg|png|gif|webp|css|js|vtt|srt)(?:\$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(decoded)) return ""
if (!PLAYABLE_RE.containsMatchIn(decoded)) return ""
return try {
baseUrl.toHttpUrl().resolve(decoded)?.toString()?: decoded
} catch (e: Exception) {
""
}
}

private fun extractMediaUrlsFromHtml(html: String, baseUrl: String): List<String> {
val candidates = ArrayList<String>()
val seen = HashSet<String>()
fun push(value: String) {
val url = normalizeExtractedMediaUrl(value, baseUrl)
if (url.isEmpty() ||!seen.add(url)) return
candidates.add(url)
}
val variableRe = Regex("""(?:const|let|var)\s+(?:url|main|video|source|src|file|playurl|player_url|m3u8|mp4)\s*=\s*(['"`])([\s\S]*?)\1""", RegexOption.IGNORE_CASE)
val propertyRe = Regex("""\b(?:url|src|file|video|source|playUrl|player_url)\s*:\s*(['"`])([\s\S]*?)\1""", RegexOption.IGNORE_CASE)
val attrRe = Regex("""\b(?:src|data-src|data-url|data-player|href)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
val quotedMediaRe = Regex("""["']([^"']+\.(?:m3u8|mpd|mp4|m4v|webm|ogv|ogg|flv|ts|m2ts|mts|mov|mkv|avi|mpeg|mpg|3gp|f4v)(?:[?#][^"']*)?)["']""", RegexOption.IGNORE_CASE)
for (m in variableRe.findAll(html)) push(m.groupValues[2])
for (m in propertyRe.findAll(html)) push(m.groupValues[2])
for (m in attrRe.findAll(html)) push(m.groupValues[1])
for (m in quotedMediaRe.findAll(html)) push(m.groupValues[1])
fun score(url: String) = when (inferMediaKind(url)) {
"hls" -> 40; "dash" -> 34; "mp4" -> 30; "flv" -> 24; "mpegts" -> 20; else -> 10
}
return candidates.sortedByDescending { score(it)}
}

suspend fun resolveMediaUrl(target: String): JSONObject {
val t = target.trim()
if (t.length < 8 || t.length > 4096) throw BridgeError("INVALID_MEDIA_URL", "Invalid media url.")
val url = try {
t.toHttpUrl()
} catch (e: Exception) {
throw BridgeError("INVALID_MEDIA_URL", "Invalid media url.")
}
if (url.scheme!= "http" && url.scheme!= "https") throw BridgeError("INVALID_MEDIA_URL", "Invalid media url.")
val original = url.toString()
val directKind = inferMediaKind(original)
if (directKind!= "page" && PLAYABLE_RE.containsMatchIn(original)) {
return jsonOf(
"originalUrl" to original, "url" to original, "resolved" to false,
"kind" to directKind, "format" to mediaFormatLabel(directKind), "contentType" to ""
)
}
cacheGet(resolveCache, original)?.let { return it}

fun payload(url2: String, resolved: Boolean, kind: String, contentType: String, candidates: List<String>?, error: String?): JSONObject {
val o = jsonOf(
"originalUrl" to original, "url" to url2, "resolved" to resolved,
"kind" to kind, "format" to mediaFormatLabel(kind), "contentType" to contentType
)
if (candidates!= null) o.put("candidates", JSONArray(candidates.take(6)))
if (error!= null) o.put("error", error)
cachePut(resolveCache, original, o, 5 * 60 * 1000, 160)
return o
}

return try {
withTimeoutOrNull(8500) {
val req = Request.Builder().url(original)
.header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 CineFlow/3.2.0")
.header("Referer", "${url.scheme}://${url.host}/")
.build()
val resp = Net.client(store.proxy).newCall(req).await()
resp.use {
val finalUrl = it.request.url.toString()
val contentType = it.header("Content-Type").orEmpty()
val responseKind = inferMediaKind(finalUrl, contentType)
val ct = contentType.lowercase()
if (responseKind!= "page" && (PLAYABLE_RE.containsMatchIn(finalUrl) ||
ct.startsWith("video/") || ct.startsWith("audio/") ||
"mpegurl" in ct || "dash+xml" in ct)
) {
return@withTimeoutOrNull payload(finalUrl, finalUrl!= original, responseKind, contentType, null, null)
}
val text = it.body?.string().orEmpty().take(2_000_000)
val candidates = extractMediaUrlsFromHtml(text, finalUrl)
val best = candidates.firstOrNull()?: finalUrl
val kind = inferMediaKind(best)
return@withTimeoutOrNull payload(best, best!= original, kind, contentType, candidates, null)
}
}?: payload(original, false, directKind, "", null, "resolve-timeout")
} catch (e: Exception) {
payload(original, false, directKind, "", null, "resolve-failed")
}
}

fun clearCache() {
synchronized(resourceCache) { resourceCache.clear()}
synchronized(resolveCache) { resolveCache.clear()}
}
}
