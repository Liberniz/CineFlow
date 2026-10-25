package com.cineflow.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cineflow.tv.R
import com.cineflow.tv.jsonOf
import com.cineflow.tv.toObjectList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var scope: CoroutineScope = newUiScope()
    private lateinit var status: TextView
    private lateinit var rowAdapter: RowAdapter
    private var loaded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!scope.isActive) scope = newUiScope()
        status = view.findViewById(R.id.homeStatus)
        val rowsList = view.findViewById<RecyclerView>(R.id.rowsList)
        rowsList.layoutManager = LinearLayoutManager(requireContext())
        rowAdapter = RowAdapter { movie ->
            startActivity(DetailActivity.intent(requireContext(), movie))
        }
        rowsList.adapter = rowAdapter
        if (!loaded) load()
    }

    private fun load() {
        if (Graph.tmdb.credential() == null) {
            status.text = "还没有配置 TMDB 凭据。\n用左侧导航到「设置」，填入 API Key 或 Read Token 后再回来。"
            return
        }
        status.text = "正在加载…"
        scope.launch {
            val data = runCatching {
                Graph.tmdb.getInitialData(jsonOf("hasCredential" to true))
            }.getOrElse { e ->
                status.text = "加载失败：${errorText(e)}\n（点这里重试）"
                status.setOnClickListener { load() }
                return@launch
            }
            loaded = true
            status.text = ""
            status.setOnClickListener(null)
            val rows = ArrayList<RowAdapter.Row>()
            data.optJSONObject("daily")?.let { rows.add(RowAdapter.Row("今日推荐", listOf(it))) }
            data.optJSONArray("trending")?.toObjectList()
                ?.takeIf { it.isNotEmpty() }
                ?.let { rows.add(RowAdapter.Row("正在流行", it)) }
            data.optJSONArray("popular")?.toObjectList()
                ?.takeIf { it.isNotEmpty() }
                ?.let { rows.add(RowAdapter.Row("大众流行", it)) }
            rowAdapter.submit(rows)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && !loaded && ::rowAdapter.isInitialized) load()
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}
