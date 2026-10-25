package com.cineflow.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cineflow.tv.R
import com.cineflow.tv.toObjectList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private var scope: CoroutineScope = newUiScope()
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var posterAdapter: PosterAdapter

    private var query: String = ""
    private var page: Int = 1
    private var loading: Boolean = false
    private var exhausted: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!scope.isActive) scope = newUiScope()
        input = view.findViewById(R.id.searchInput)
        status = view.findViewById(R.id.searchStatus)
        status.text = "输入片名开始搜索"

        val (span, itemWidth) = requireContext().gridGeometry()
        val grid = view.findViewById<RecyclerView>(R.id.searchGrid)
        grid.layoutManager = GridLayoutManager(requireContext(), span)
        posterAdapter = PosterAdapter(
            onClick = { movie -> startActivity(DetailActivity.intent(requireContext(), movie)) },
            gridMode = true,
            gridItemWidthPx = itemWidth,
            onNearEnd = { loadMore() }
        )
        grid.adapter = posterAdapter

        view.findViewById<TextView>(R.id.searchGoBtn).setOnClickListener { startSearch() }
        view.findViewById<TextView>(R.id.searchScanBtn).setOnClickListener {
            ScanInputDialog(requireContext(), "搜索影片") { text ->
                input.setText(text)
                startSearch()
            }.show()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else {
                false
            }
        }
    }

    private fun startSearch() {
        val q = input.text.toString().trim()
        if (q.isBlank()) {
            status.text = "先输入片名"
            return
        }
        query = q
        page = 1
        exhausted = false
        loadPage(reset = true)
    }

    private fun loadMore() {
        if (loading || exhausted || query.isBlank()) return
        page += 1
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) status.text = "正在搜索…"
        val targetPage = page
        val q = query
        scope.launch {
            val arr = runCatching { Graph.tmdb.searchMovies(q, targetPage, true) }.getOrNull()
            loading = false
            if (arr == null) {
                if (reset) status.text = "搜索失败，请检查网络或 TMDB 凭据"
                return@launch
            }
            val list = arr.toObjectList()
            if (list.isEmpty()) exhausted = true
            if (reset) {
                posterAdapter.submit(list)
                status.text = if (list.isEmpty()) "没有找到「$q」" else ""
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
