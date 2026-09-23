package com.pandorasbox.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class PlaylistAdapter(
    private val entries: MutableList<PlaylistEntry>,
    private val onItemChanged: () -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbInclude: CheckBox = view.findViewById(R.id.cb_include)
        val tvIndex: TextView = view.findViewById(R.id.tv_index)
        val tvTitle: TextView = view.findViewById(R.id.tv_item_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = entries[position]
        holder.cbInclude.isChecked = item.included
        holder.tvIndex.text = String.format(Locale.US, "%02d", position + 1)
        holder.tvTitle.text = item.title

        holder.cbInclude.setOnCheckedChangeListener { _, isChecked ->
            item.included = isChecked
            onItemChanged()
        }
    }

    override fun getItemCount(): Int = entries.size
}
