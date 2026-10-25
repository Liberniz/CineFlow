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

class FavoritesFragment : Fragment() {

    private lateinit var empty: TextView
    private lateinit var posterAdapter: PosterAdapter
    private var sortMode: String = "recent"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_favorites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        empty = view.findViewById(R.id.favEmpty)
        val (span, itemWidth) = requireContext().gridGeometry()
        val grid = view.findViewById<RecyclerView>(R.id.favGrid)
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        posterAdapter = PosterAdapter(
            onClick = { movie -> startActivity(DetailActivity.intent(requireContext(), movie)) },
            gridMode = true,
            gridItemWidthPx = itemWidth
        )
        grid.adapter = posterAdapter

        val sortList = view.findViewById<RecyclerView>(R.id.favSortChips)
        sortList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val sortAdapter = ChipAdapter { chip ->
            sortMode = chip.payload
            refresh()
        }
        sortList.adapter = sortAdapter
        sortAdapter.submit(
            listOf(
                ChipAdapter.Chip(0, "收藏顺序", "recent"),
                ChipAdapter.Chip(1, "评分最高", "rating"),
                ChipAdapter.Chip(2, "年份最新", "yearDesc"),
                ChipAdapter.Chip(3, "年份最早", "yearAsc")
            ), 0
        )
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::posterAdapter.isInitialized) refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::posterAdapter.isInitialized) refresh()
    }

    private fun refresh() {
        val favs = Favorites.all()
        empty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
        val sorted = when (sortMode) {
            "rating" -> favs.sortedByDescending { it.optDouble("voteAverage", 0.0) }
            "yearDesc" -> favs.sortedByDescending { it.optString("releaseDate") }
            "yearAsc" -> favs.sortedBy { it.optString("releaseDate") }
            else -> favs
        }
        posterAdapter.submit(sorted)
    }
}
