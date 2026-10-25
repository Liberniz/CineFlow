package com.cineflow.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cineflow.tv.R
import org.json.JSONObject

/** Home screen: vertical list of titled horizontal poster rows. */
class RowAdapter(
    private val onClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    data class Row(val title: String, val items: List<JSONObject>)

    private val rows = ArrayList<Row>()

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.rowTitle)
        val list: RecyclerView = view.findViewById(R.id.rowList)
        lateinit var posters: PosterAdapter
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_row, parent, false)
        val vh = VH(v)
        vh.posters = PosterAdapter(onClick = onClick)
        vh.list.layoutManager = LinearLayoutManager(
            parent.context, LinearLayoutManager.HORIZONTAL, false
        )
        vh.list.adapter = vh.posters
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.posters.submit(row.items)
    }

    override fun getItemCount(): Int = rows.size
}
