package com.cineflow.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cineflow.tv.R
import com.cineflow.tv.toObjectList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var scope: CoroutineScope = newUiScope()
    private lateinit var stateText: TextView
    private lateinit var credentialInput: EditText
    private lateinit var proxyInput: EditText
    private lateinit var sourcesInput: EditText
    private lateinit var sourcesInfo: TextView
    private lateinit var modeAdapter: ChipAdapter

    private val proxyPattern = Regex("^(https?|socks5)://\\S+$", RegexOption.IGNORE_CASE)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!scope.isActive) scope = newUiScope()
        stateText = view.findViewById(R.id.settingsState)
        credentialInput = view.findViewById(R.id.credentialInput)
        proxyInput = view.findViewById(R.id.proxyInput)
        sourcesInput = view.findViewById(R.id.sourcesInput)
        sourcesInfo = view.findViewById(R.id.sourcesInfo)

        credentialInput.setText(Graph.store.tmdbCredential.orEmpty())
        proxyInput.setText(Graph.store.proxy)

        val modeList = view.findViewById<RecyclerView>(R.id.modeChips)
        modeList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        modeAdapter = ChipAdapter { chip -> saveMode(chip.payload) }
        modeList.adapter = modeAdapter

        view.findViewById<TextView>(R.id.credentialScanBtn).setOnClickListener {
            ScanInputDialog(requireContext(), "TMDB API Key 或 Read Access Token") { text ->
                credentialInput.setText(text)
            }.show()
        }
        view.findViewById<TextView>(R.id.credentialSaveBtn).setOnClickListener {
            val value = credentialInput.text.toString().trim()
            Graph.store.tmdbCredential = value.ifEmpty { null }
            Graph.tmdb.clearCache()
            requireContext().toastShort("凭据已保存")
            refreshState()
        }
        view.findViewById<TextView>(R.id.credentialClearBtn).setOnClickListener {
            Graph.store.tmdbCredential = null
            credentialInput.setText("")
            Graph.tmdb.clearCache()
            requireContext().toastShort("凭据已清除")
            refreshState()
        }

        view.findViewById<TextView>(R.id.proxyScanBtn).setOnClickListener {
            ScanInputDialog(requireContext(), "代理地址") { text ->
                proxyInput.setText(text)
            }.show()
        }
        view.findViewById<TextView>(R.id.proxySaveBtn).setOnClickListener {
            saveProxy()
        }

        view.findViewById<TextView>(R.id.sourcesScanBtn).setOnClickListener {
            ScanInputDialog(requireContext(), "自定义外部源导入") { text ->
                sourcesInput.setText(text)
            }.show()
        }
        view.findViewById<TextView>(R.id.sourcesImportBtn).setOnClickListener {
            val text = sourcesInput.text.toString()
            if (text.isBlank()) {
                requireContext().toastShort("先粘贴要导入的内容")
                return@setOnClickListener
            }
            scope.launch {
                runCatching { Graph.resources.importResourceSources(text) }
                    .onSuccess {
                        sourcesInput.setText("")
                        requireContext().toastShort("导入完成")
                        refreshState()
                    }
                    .onFailure { e ->
                        requireContext().toastLong("导入失败：${errorText(e)}")
                    }
            }
        }
        view.findViewById<TextView>(R.id.sourcesClearBtn).setOnClickListener {
            Graph.resources.clearResourceSources()
            requireContext().toastShort("自定义源已清空")
            refreshState()
        }

        view.findViewById<TextView>(R.id.testConnectionBtn).setOnClickListener {
            requireContext().toastShort("正在测试…")
            scope.launch {
                runCatching { Graph.tmdb.tmdbFetch("/configuration") }
                    .onSuccess { requireContext().toastShort("连接成功") }
                    .onFailure { e -> requireContext().toastLong("连接失败：${errorText(e)}") }
            }
        }

        refreshState()
    }

    private fun saveProxy() {
        val raw = proxyInput.text.toString().trim()
        val valid = raw.isEmpty() || raw == "system" || raw == "direct" || proxyPattern.matches(raw)
        Graph.store.proxy = if (valid) raw.ifEmpty { "system" } else "system"
        if (!valid) proxyInput.setText("system")
        // Images and API clients pick the proxy up per request; the Coil
        // loader holds its own client, so rebuild it.
        Images.applyLoader(requireContext())
        Graph.tmdb.clearCache()
        requireContext().toastShort(
            if (valid) "代理已保存" else "代理格式不对，已恢复为 system"
        )
        refreshState()
    }

    private fun saveMode(value: String) {
        if (value.isBlank()) return
        Graph.store.resourceMode = value
        Graph.resources.clearCache()
        requireContext().toastShort("资源模式已切换")
    }

    private fun refreshState() {
        val has = Graph.tmdb.credential() != null
        stateText.text = if (has) "TMDB 凭据：已配置" else "TMDB 凭据：未配置（填好后保存）"
        val settings = runCatching { Graph.resources.publicResourceSettings() }.getOrNull()
            ?: return
        sourcesInfo.text = "共 ${settings.optInt("sourceCount")} 个源" +
            "（内置 ${settings.optInt("builtInSourceCount")} + 自定义 ${settings.optInt("customSourceCount")}）"
        val modes = settings.optJSONArray("modes")?.toObjectList().orEmpty()
        val current = Graph.store.resourceMode
        modeAdapter.submit(
            modes.mapIndexed { index, m ->
                ChipAdapter.Chip(index, m.optString("label"), m.optString("value"))
            },
            modes.indexOfFirst { it.optString("value") == current }.coerceAtLeast(0)
        )
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}
