package com.cineflow.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cineflow.tv.R

/** Single-select pill chips (genres, lines, modes, media types). */
class ChipAdapter(
    private val onSelect: (Chip) -> Unit
) : RecyclerView.Adapter<ChipAdapter.VH>() {

    data class Chip(val id: Int, val label: String, val payload: String = "")

    private val chips = ArrayList<Chip>()
    var selectedIndex: Int = -1
        private set

    fun submit(list: List<Chip>, selected: Int = 0) {
        chips.clear()
        chips.addAll(list)
        selectedIndex = if (list.isEmpty()) -1 else selected.coerceIn(0, list.size - 1)
        notifyDataSetChanged()
    }

    fun selectedChip(): Chip? = chips.getOrNull(selectedIndex)

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chip, parent, false) as TextView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = chips[position]
        holder.text.text = chip.label
        holder.text.isSelected = position == selectedIndex
        holder.text.setOnClickListener {
            if (selectedIndex == position) return@setOnClickListener
            val old = selectedIndex
            selectedIndex = position
            if (old >= 0) notifyItemChanged(old)
            notifyItemChanged(position)
            onSelect(chip)
        }
    }

    override fun getItemCount(): Int = chips.size
}
