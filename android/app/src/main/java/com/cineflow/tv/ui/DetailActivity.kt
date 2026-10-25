package com.cineflow.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.cineflow.tv.PlayerActivity
import com.cineflow.tv.R
import com.cineflow.tv.jsonOf
import com.cineflow.tv.toObjectList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Native detail screen: TMDB metadata + resource lookup + episode pick,
 * playback handed to the existing native PlayerActivity.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TYPE = "mediaType"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DATE = "releaseDate"
        private const val EXTRA_POSTER = "posterPath"

        fun intent(context: Context, movie: JSONObject): Intent =
            Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_ID, movie.optLong("id"))
                .putExtra(EXTRA_TYPE, movie.optString("mediaType", "movie"))
                .putExtra(EXTRA_TITLE, movie.optString("title"))
                .putExtra(EXTRA_DATE, movie.optString("releaseDate"))
                .putExtra(EXTRA_POSTER, movie.optString("posterPath"))
    }

    private val scope: CoroutineScope = newUiScope()

    private var movieId: Long = 0
    private var mediaType: String = "movie"
    private var extraTitle: String = ""
    private var extraDate: String = ""
    private var extraPoster: String = ""

    private var details: JSONObject? = null
    private val sourceViews = LinkedHashMap<String, View>()
    private var selectedSource: JSONObject? = null

    private lateinit var scroll: android.widget.ScrollView
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var genresText: TextView
    private lateinit var overviewText: TextView
    private lateinit var playBtn: TextView
    private lateinit var favBtn: TextView
    private lateinit var sourcesStatus: TextView
    private lateinit var sourcesContainer: LinearLayout
    private lateinit var lineChipsList: RecyclerView
    private lateinit var episodeGrid: RecyclerView
    private lateinit var castTitle: TextView
    private lateinit var castList: RecyclerView
    private lateinit var recTitle: TextView
    private lateinit var recList: RecyclerView

    private lateinit var lineAdapter: ChipAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.ensure(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(R.layout.activity_detail)

        movieId = intent.getLongExtra(EXTRA_ID, 0)
        mediaType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        extraTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        extraDate = intent.getStringExtra(EXTRA_DATE).orEmpty()
        extraPoster = intent.getStringExtra(EXTRA_POSTER).orEmpty()
        if (movieId == 0L) {
            finish()
            return
        }

        scroll = findViewById(R.id.detailScroll)
        backdrop = findViewById(R.id.detailBackdrop)
        poster = findViewById(R.id.detailPoster)
        titleText = findViewById(R.id.detailTitle)
        metaText = findViewById(R.id.detailMeta)
        genresText = findViewById(R.id.detailGenres)
        overviewText = findViewById(R.id.detailOverview)
        playBtn = findViewById(R.id.detailPlayBtn)
        favBtn = findViewById(R.id.detailFavBtn)
        sourcesStatus = findViewById(R.id.sourcesStatus)
        sourcesContainer = findViewById(R.id.sourcesContainer)
        lineChipsList = findViewById(R.id.lineChips)
        episodeGrid = findViewById(R.id.episodeGrid)
        castTitle = findViewById(R.id.castTitle)
        castList = findViewById(R.id.castList)
        recTitle = findViewById(R.id.recTitle)
        recList = findViewById(R.id.recList)

        // Instant paint from the card data while details load.
        titleText.text = extraTitle
        metaText.text = listOf(
            extraDate.take(4), if (mediaType == "tv") "剧集" else "电影"
        ).filter { it.isNotBlank() }.joinToString(" · ")
        Images.url(extraPoster, "w185")?.let { poster.load(it) }
        refreshFavBtn()

        lineAdapter = ChipAdapter { chip ->
            val source = selectedSource ?: return@ChipAdapter
            val lines = source.optJSONArray("lines")?.toObjectList().orEmpty()
            lines.getOrNull(chip.id)?.let { selectLine(it) }
        }
        lineChipsList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        lineChipsList.adapter = lineAdapter

        episodeAdapter = EpisodeAdapter { ep -> playEpisode(ep) }
        episodeGrid.layoutManager = GridLayoutManager(this, 4)
        episodeGrid.adapter = episodeAdapter

        castList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        playBtn.setOnClickListener {
            val first = sourceViews.values.firstOrNull()
            if (first != null) {
                // requestFocus makes the ScrollView reveal it on its own.
                first.requestFocus()
            } else {
                toastShort("资源还在查找中…")
            }
        }
        favBtn.setOnClickListener {
            val compact = compactForFavorite() ?: return@setOnClickListener
            val nowFav = Favorites.toggle(compact)
            refreshFavBtn()
            toastShort(if (nowFav) "已加入收藏" else "已取消收藏")
        }
        playBtn.requestFocus()

        loadDetails()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // details
    // ------------------------------------------------------------------

    private fun loadDetails() {
        scope.launch {
            val d = runCatching { Graph.tmdb.getMovieDetails(movieId, mediaType) }
                .getOrElse { e ->
                    toastLong("详情加载失败：${errorText(e)}")
                    loadResources(fallbackPayload())
                    return@launch
                }
            details = d
            bindDetails(d)
            refreshFavBtn()
            loadResources(d)
        }
    }

    private fun bindDetails(d: JSONObject) {
        titleText.text = d.optString("title")
        val meta = ArrayList<String>()
        d.optString("releaseDate").take(4).takeIf { it.isNotBlank() }?.let { meta.add(it) }
        if (mediaType == "tv") {
            val seasons = d.optInt("numberOfSeasons", 0)
            val episodes = d.optInt("numberOfEpisodes", 0)
            if (seasons > 0) meta.add("$seasons 季 · $episodes 集")
        } else {
            val runtime = d.optInt("runtime", 0)
            if (runtime > 0) meta.add("$runtime 分钟")
        }
        val rating = d.optDouble("voteAverage", 0.0)
        if (rating > 0) meta.add("★" + String.format("%.1f", rating))
        metaText.text = meta.joinToString(" · ")
        val genres = d.optJSONArray("genres")?.toObjectList()
            ?.map { it.optString("name") }?.filter { it.isNotBlank() }
        genresText.text = genres?.joinToString(" / ").orEmpty()
        overviewText.text = d.optString("overview").ifBlank { "暂无简介" }
        Images.url(d.optString("backdropPath"), "w780")?.let { backdrop.load(it) }
        Images.url(d.optString("posterPath"), "w185")?.let { poster.load(it) }

        val cast = d.optJSONArray("cast")?.toObjectList().orEmpty()
        if (cast.isEmpty()) {
            castTitle.visibility = View.GONE
            castList.visibility = View.GONE
        } else {
            castList.adapter = CastAdapter(cast)
        }

        val recs = d.optJSONArray("recommendations")?.toObjectList().orEmpty()
            .ifEmpty { d.optJSONArray("similar")?.toObjectList().orEmpty() }
        if (recs.isEmpty()) {
            recTitle.visibility = View.GONE
            recList.visibility = View.GONE
        } else {
            recList.adapter = PosterAdapter(onClick = { movie ->
                startActivity(intent(this@DetailActivity, movie))
            }).apply { submit(recs) }
        }
    }

    private fun fallbackPayload(): JSONObject = jsonOf(
        "id" to movieId,
        "mediaType" to mediaType,
        "title" to extraTitle,
        "originalTitle" to "",
        "releaseDate" to extraDate
    )

    private fun compactForFavorite(): JSONObject? {
        val d = details
        return jsonOf(
            "id" to movieId,
            "mediaType" to mediaType,
            "title" to (d?.optString("title")?.ifBlank { null } ?: extraTitle),
            "originalTitle" to (d?.optString("originalTitle") ?: ""),
            "overview" to (d?.optString("overview") ?: ""),
            "posterPath" to (d?.optString("posterPath")?.ifBlank { null } ?: extraPoster),
            "backdropPath" to (d?.optString("backdropPath") ?: ""),
            "voteAverage" to (d?.optDouble("voteAverage", 0.0) ?: 0.0),
            "voteCount" to (d?.optInt("voteCount", 0) ?: 0),
            "releaseDate" to (d?.optString("releaseDate")?.ifBlank { null } ?: extraDate)
        )
    }

    private fun refreshFavBtn() {
        val fav = Favorites.contains(mediaType, movieId)
        favBtn.text = if (fav) "★ 已收藏" else "☆ 收藏"
    }

    // ------------------------------------------------------------------
    // resources
    // ------------------------------------------------------------------

    private fun loadResources(payload: JSONObject) {
        scope.launch {
            sourcesStatus.text = "正在查找资源…"
            var cursor = 0
            var done = false
            var any = false
            while (!done) {
                val page = runCatching {
                    Graph.resources.findMovieResources(payload, cursor, 3)
                }.getOrNull() ?: break
                val found = page.optJSONArray("resources")?.toObjectList().orEmpty()
                if (found.isNotEmpty()) {
                    val wasEmpty = !any
                    any = true
                    found.forEach { addSourceView(it) }
                    if (wasEmpty) selectSource(found.first())
                }
                cursor = page.optInt("nextCursor", cursor)
                done = page.optBoolean("done", true)
                if (!done) {
                    sourcesStatus.text = "正在查找资源… ${page.optInt("checked")}/${page.optInt("total")}"
                }
            }
            sourcesStatus.text = if (any) "" else {
                "没有找到可用资源（可在设置里调整资源模式或导入自定义源）"
            }
        }
    }

    private fun addSourceView(source: JSONObject) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_source, sourcesContainer, false)
        view.tag = source
        view.findViewById<TextView>(R.id.sourceName).text = source.optString("sourceName")
        view.findViewById<TextView>(R.id.sourceRemarks).text = source.optString("remarks")
        val formats = source.optJSONArray("formats")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()
        val meta = ArrayList<String>()
        if (formats.isNotEmpty()) meta.add(formats.joinToString(" "))
        meta.add("共 ${source.optInt("episodeCount")} 集")
        meta.add("${source.optInt("playGroupCount")} 条线路")
        view.findViewById<TextView>(R.id.sourceMeta).text = meta.joinToString(" · ")
        view.setOnClickListener { selectSource(source) }
        val key = source.optString("sourceKey")
        sourceViews[key] = view
        view.isSelected = source === selectedSource
        sourcesContainer.addView(view)
    }

    private fun selectSource(source: JSONObject) {
        selectedSource = source
        sourceViews.forEach { (key, view) ->
            view.isSelected = key == source.optString("sourceKey")
        }
        val lines = source.optJSONArray("lines")?.toObjectList().orEmpty()
        lineAdapter.submit(
            lines.mapIndexed { index, line -> ChipAdapter.Chip(index, line.optString("name")) },
            0
        )
        lineChipsList.visibility = if (lines.size > 1) View.VISIBLE else View.GONE
        lines.firstOrNull()?.let { selectLine(it) }
    }

    private fun selectLine(line: JSONObject) {
        val episodes = line.optJSONArray("episodes")?.toObjectList().orEmpty()
        episodeAdapter.submit(episodes)
    }

    // ------------------------------------------------------------------
    // playback
    // ------------------------------------------------------------------

    private fun playEpisode(episode: JSONObject) {
        val rawUrl = episode.optString("url")
        val rawKind = episode.optString("kind")
        val needsResolve = episode.optBoolean("needsResolve") || rawKind == "page"
        val title = "${details?.optString("title")?.ifBlank { null } ?: extraTitle} · ${episode.optString("label")}"
        if (!needsResolve) {
            launchPlayer(rawUrl, rawKind, title)
            return
        }
        toastShort("正在解析播放地址…")
        scope.launch {
            val resolved = runCatching { Graph.resources.resolveMediaUrl(rawUrl) }.getOrNull()
            val url = resolved?.optString("url").orEmpty()
            val kind = resolved?.optString("kind").orEmpty()
            if (resolved != null && url.isNotBlank() && kind != "page" && kind.isNotBlank()) {
                launchPlayer(url, kind, title)
            } else {
                toastLong("这个资源需要解析，未能得到可播放地址，换一条线路试试")
            }
        }
    }

    private fun launchPlayer(url: String, kind: String, title: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_KIND, kind)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
        )
    }
}

/** Episode buttons for one play line. */
class EpisodeAdapter(
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val items = ArrayList<JSONObject>()

    fun submit(list: List<JSONObject>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.text.text = ep.optString("label")
        holder.text.setOnClickListener { onClick(ep) }
    }

    override fun getItemCount(): Int = items.size
}

/** Cast row: round profile photo + name + character. */
class CastAdapter(
    private val cast: List<JSONObject>
) : RecyclerView.Adapter<CastAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.castImage)
        val name: TextView = view.findViewById(R.id.castName)
        val role: TextView = view.findViewById(R.id.castRole)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cast, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val person = cast[position]
        holder.name.text = person.optString("name")
        holder.role.text = person.optString("character")
        val url = Images.url(person.optString("profilePath"), "w185")
        if (url != null) {
            holder.image.load(url) { transformations(CircleCropTransformation()) }
        } else {
            holder.image.setImageDrawable(null)
        }
    }

    override fun getItemCount(): Int = cast.size
}
