package com.cineflow.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cineflow.tv.R
import com.cineflow.tv.jsonOf
import com.cineflow.tv.toObjectList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BrowseFragment : Fragment() {

    private var scope: CoroutineScope = newUiScope()
    private lateinit var status: TextView
    private lateinit var genreAdapter: ChipAdapter
    private lateinit var posterAdapter: PosterAdapter

    private var mediaType: String = "all"
    private var genreId: Int = 0
    private var page: Int = 1
    private var loading: Boolean = false
    private var exhausted: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_browse, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!scope.isActive) scope = newUiScope()
        status = view.findViewById(R.id.browseStatus)

        val typeList = view.findViewById<RecyclerView>(R.id.typeChips)
        typeList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val typeAdapter = ChipAdapter { chip ->
            mediaType = chip.payload
            reload()
        }
        typeList.adapter = typeAdapter
        typeAdapter.submit(
            listOf(
                ChipAdapter.Chip(0, "全部", "all"),
                ChipAdapter.Chip(1, "电影", "movie"),
                ChipAdapter.Chip(2, "剧集", "tv")
            ), 0
        )

        val genreList = view.findViewById<RecyclerView>(R.id.genreChips)
        genreList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        genreAdapter = ChipAdapter { chip ->
            genreId = chip.id
            reload()
        }
        genreList.adapter = genreAdapter
        genreAdapter.submit(listOf(ChipAdapter.Chip(0, "全部")), 0)

        val (span, itemWidth) = requireContext().gridGeometry()
        val grid = view.findViewById<RecyclerView>(R.id.browseGrid)
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        posterAdapter = PosterAdapter(
            onClick = { movie -> startActivity(DetailActivity.intent(requireContext(), movie)) },
            gridMode = true,
            gridItemWidthPx = itemWidth,
            onNearEnd = { loadMore() }
        )
        grid.adapter = posterAdapter

        loadGenres()
        reload()
    }

    private fun loadGenres() {
        scope.launch {
            val data = runCatching {
                Graph.tmdb.getInitialData(jsonOf("hasCredential" to true))
            }.getOrNull() ?: return@launch
            val genres = data.optJSONArray("genres")?.toObjectList().orEmpty()
            if (genres.isEmpty()) return@launch
            val chips = ArrayList<ChipAdapter.Chip>()
            chips.add(ChipAdapter.Chip(0, "全部"))
            genres.forEach { g ->
                val name = g.optString("name")
                if (name.isNotBlank()) chips.add(ChipAdapter.Chip(g.optInt("id"), name))
            }
            val selected = chips.indexOfFirst { it.id == genreId }.coerceAtLeast(0)
            genreAdapter.submit(chips, selected)
        }
    }

    private fun reload() {
        page = 1
        exhausted = false
        loadPage(reset = true)
    }

    private fun loadMore() {
        if (loading || exhausted) return
        page += 1
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) status.text = "正在加载…"
        val targetPage = page
        scope.launch {
            val arr = runCatching {
                Graph.tmdb.discoverMovies(
                    jsonOf(
                        "mediaType" to mediaType,
                        "genreId" to genreId,
                        "page" to targetPage,
                        "sortBy" to "popularity.desc"
                    )
                )
            }.getOrNull()
            loading = false
            if (arr == null) {
                if (reset) status.text = "加载失败，请检查网络或 TMDB 凭据"
                return@launch
            }
            val list = arr.toObjectList()
            if (list.isEmpty()) exhausted = true
            if (reset) {
                posterAdapter.submit(list)
                status.text = if (list.isEmpty()) "这个分类暂时没有内容" else ""
            } else {
                posterAdapter.append(list)
            }
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}
