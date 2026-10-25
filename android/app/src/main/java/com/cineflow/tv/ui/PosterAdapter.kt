package com.cineflow.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cineflow.tv.R
import org.json.JSONObject

/**
 * Poster cards (item_poster). Used horizontally in rows and in grids;
 * grid mode stretches the card to the column width computed by the host.
 */
class PosterAdapter(
    private val onClick: (JSONObject) -> Unit,
    private val gridMode: Boolean = false,
    private val gridItemWidthPx: Int = 0,
    private val onNearEnd: (() -> Unit)? = null
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private val items = ArrayList<JSONObject>()

    fun submit(list: List<JSONObject>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<JSONObject>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun count(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val image: ImageView = view.findViewById(R.id.posterImage)
        val title: TextView = view.findViewById(R.id.posterTitle)
        val meta: TextView = view.findViewById(R.id.posterMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster, parent, false)
        if (gridMode && gridItemWidthPx > 0) {
            v.layoutParams = v.layoutParams.apply { width = gridItemWidthPx }
            val inner = gridItemWidthPx - v.paddingLeft - v.paddingRight
            val img = v.findViewById<ImageView>(R.id.posterImage)
            img.layoutParams = img.layoutParams.apply {
                width = inner
                height = (inner * 1.5f).toInt()
            }
            for (id in intArrayOf(R.id.posterTitle, R.id.posterMeta)) {
                val tv = v.findViewById<TextView>(id)
                tv.layoutParams = tv.layoutParams.apply { width = inner }
            }
        }
        v.attachFocusScale()
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.root.scaleX = 1f
        holder.root.scaleY = 1f
        holder.title.text = item.optString("title")
        holder.meta.text = item.movieMeta()
        val url = Images.url(item.optString("posterPath"))
        if (url != null) {
            holder.image.load(url)
        } else {
            holder.image.setImageDrawable(null)
        }
        holder.root.setOnClickListener { onClick(item) }
        if (onNearEnd != null && position >= items.size - 4) onNearEnd.invoke()
    }

    override fun getItemCount(): Int = items.size
}
