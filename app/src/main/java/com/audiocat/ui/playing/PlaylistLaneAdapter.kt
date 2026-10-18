package com.audiocat.ui.playing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.R
import com.audiocat.databinding.ItemPlaylistLaneBinding

/** One card in the playlist lane. */
sealed class LaneItem {

    /** The playlist id this card selects, or null for the whole library. */
    abstract val playlistId: Long?

    /** The library, always the first card so "show everything" is reachable. */
    data class All(val trackCount: Int) : LaneItem() {
        override val playlistId: Long? get() = null
    }

    data class Playlist(val row: PlaylistRow, override val playlistId: Long) : LaneItem()

    val title: String
        get() = when (this) {
            is All -> ALL_TITLE
            is Playlist -> row.playlist.name
        }

    val count: Int
        get() = when (this) {
            is All -> trackCount
            is Playlist -> row.trackCount
        }

    private companion object {
        const val ALL_TITLE = "All songs"
    }
}

/**
 * The playlist lane above the library.
 *
 * Tapping a card chooses what the list below shows; the selected card is the one
 * whose id matches [selectedId]. A long press opens the card's actions, which is
 * the only place a playlist can be renamed or deleted. Dragging the handle at
 * the leading edge of a card reorders the lane — one card can ride another to
 * any position, and the new order is handed back through [onMoved].
 */
class PlaylistLaneAdapter(
    private val onClick: (LaneItem) -> Unit,
    private val onLongClick: (LaneItem) -> Unit,
    private val onMoved: (from: Int, to: Int) -> Unit = { _, _ -> },
    private var selectedId: Long? = null,
) : ListAdapter<LaneItem, PlaylistLaneAdapter.LaneViewHolder>(DIFF) {

    fun setSelectedId(id: Long?) {
        if (selectedId == id) return
        selectedId = id
        // The lane is a handful of cards; rebinding it is cheaper than tracking
        // which two positions changed.
        notifyItemRangeChanged(0, itemCount)
    }

    /** Drag handle attached by [androidx.recyclerview.widget.ItemTouchHelper]. */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LaneViewHolder =
        LaneViewHolder(ItemPlaylistLaneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: LaneViewHolder, position: Int) = holder.bind(getItem(position))

    /** Local reorder that tracks the finger without waiting for the owner. */
    fun moveLaneItem(from: Int, to: Int) {
        val current = currentList.toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        // The "All songs" card is the universe scope — it always stays first.
        if (from == 0 || to == 0) return
        val moved = current.removeAt(from)
        current.add(to, moved)
        submitList(current)
    }

    fun committedOrder(): List<LaneItem> = currentList

    inner class LaneViewHolder(
        private val binding: ItemPlaylistLaneBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.laneRoot.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
            binding.laneRoot.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    false
                } else {
                    onLongClick(getItem(position))
                    true
                }
            }
            // Drag only begins on the handle, not the whole card, so taps keep
            // selecting and the row does not fight the touch for ownership.
            binding.laneDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }

        fun bind(item: LaneItem) {
            val context = binding.root.context
            binding.laneTitle.text = item.title
            binding.laneCount.text = context.resources.getQuantityString(
                R.plurals.playlist_track_count,
                item.count,
                item.count,
            )
            binding.laneArt.setImageResource(
                when (item) {
                    is LaneItem.All -> R.drawable.ic_tab_playing
                    is LaneItem.Playlist -> R.drawable.ic_queue
                },
            )
            binding.laneArt.setColorFilter(
                androidx.core.content.ContextCompat.getColor(context, R.color.ink_meta),
            )
            binding.laneRoot.isSelected = item.playlistId == selectedId
            // "All songs" is pinned to the front; it has no business being dragged.
            binding.laneDragHandle.isVisible = item !is LaneItem.All
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LaneItem>() {
            override fun areItemsTheSame(oldItem: LaneItem, newItem: LaneItem) =
                oldItem.playlistId == newItem.playlistId

            override fun areContentsTheSame(oldItem: LaneItem, newItem: LaneItem) =
                oldItem == newItem
        }
    }
}
