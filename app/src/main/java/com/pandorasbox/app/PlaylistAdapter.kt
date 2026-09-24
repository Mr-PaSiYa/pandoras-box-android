package com.pandorasbox.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.abs

/**
 * Playlist rows with an include checkbox and a drag handle.
 *
 * [entries] is changed in place: dragging reorders it and the checkbox flips
 * PlaylistEntry.included, so whoever owns the list always sees the current order and selection.
 *
 * The number shown on a row counts only INCLUDED items, in list order, so excluded videos never
 * leave gaps (excluded rows show a dash). [onItemChanged] is called after every toggle and move.
 */
class PlaylistAdapter(
    private val entries: MutableList<PlaylistEntry>,
    private val onItemChanged: () -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbInclude: CheckBox = view.findViewById(R.id.cb_include)
        val tvIndex: TextView = view.findViewById(R.id.tv_index)
        val tvTitle: TextView = view.findViewById(R.id.tv_item_title)
        val ivDragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
    }

    private var touchHelper: ItemTouchHelper? = null

    /** Turns on drag-to-reorder. Call once, right after this adapter is set on [recyclerView]. */
    fun enableDragReorder(recyclerView: RecyclerView) {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = ItemTouchHelper(DragCallback()).also { it.attachToRecyclerView(recyclerView) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        // Happens when a new adapter replaces this one (e.g. another playlist was loaded).
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_entry, parent, false)
        val holder = ViewHolder(view)

        // A click listener (not OnCheckedChangeListener) so that setting isChecked while a
        // row is being bound can never fire it and change some other row's data.
        holder.cbInclude.setOnClickListener {
            val position = holder.position()
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            entries[position].included = holder.cbInclude.isChecked
            refreshNumbers()
            onItemChanged()
        }

        // Dragging starts only from the handle, so scrolling the list and tapping the
        // checkbox keep working normally.
        holder.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchHelper?.startDrag(holder)
            }
            false
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = entries[position]
        holder.cbInclude.isChecked = item.included
        holder.tvTitle.text = item.title
        bindNumber(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Only the number / dimming changed; leave the checkbox and title alone.
            bindNumber(holder, position)
        }
    }

    override fun getItemCount(): Int = entries.size

    // ---- numbering ---------------------------------------------------------------------

    private fun bindNumber(holder: ViewHolder, position: Int) {
        val number = numberAt(position)
        val included = number > 0
        holder.tvIndex.text = if (included) String.format(Locale.US, "%02d", number) else "\u2013"
        val alpha = if (included) 1f else EXCLUDED_ALPHA
        holder.tvIndex.alpha = alpha
        holder.tvTitle.alpha = alpha
    }

    /** 1-based number among included items, or 0 if the item at [position] is excluded. */
    private fun numberAt(position: Int): Int {
        if (!entries[position].included) return 0
        var number = 0
        for (i in 0..position) {
            if (entries[i].included) number++
        }
        return number
    }

    /** Every number after a toggled row can shift, so all bound rows are refreshed. */
    private fun refreshNumbers() {
        notifyItemRangeChanged(0, entries.size, PAYLOAD_NUMBERS)
    }

    // ---- drag and drop -----------------------------------------------------------------

    private fun moveEntry(from: Int, to: Int) {
        entries.add(to, entries.removeAt(from))
        notifyItemMoved(from, to)
        // Numbers can only change for rows between the two positions.
        notifyItemRangeChanged(minOf(from, to), abs(from - to) + 1, PAYLOAD_NUMBERS)
        onItemChanged()
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0
    ) {
        // Drags start from the handle (see onCreateViewHolder), never from a long press.
        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.position()
            val to = target.position()
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            moveEntry(from, to)
            return true
        }

        // Swiping is disabled (swipeDirs = 0), so this is never called.
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }

    /** adapterPosition works with every RecyclerView version (bindingAdapterPosition needs 1.2+). */
    @Suppress("DEPRECATION")
    private fun RecyclerView.ViewHolder.position(): Int = adapterPosition

    private companion object {
        const val PAYLOAD_NUMBERS = "numbers"
        const val EXCLUDED_ALPHA = 0.45f
    }
}