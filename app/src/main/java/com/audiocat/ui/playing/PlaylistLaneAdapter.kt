package com.audiocat.ui.playing

import android.view.LayoutInflater
import android.view.ViewGroup
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
 * the only place a playlist can be renamed or deleted.
 */
class PlaylistLaneAdapter(
    private val onClick: (LaneItem) -> Unit,
    private val onLongClick: (LaneItem) -> Unit,
    private var selectedId: Long? = null,
) : ListAdapter<LaneItem, PlaylistLaneAdapter.LaneViewHolder>(DIFF) {

    fun setSelectedId(id: Long?) {
        if (selectedId == id) return
        selectedId = id
        // The lane is a handful of cards; rebinding it is cheaper than tracking
        // which two positions changed.
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LaneViewHolder =
        LaneViewHolder(ItemPlaylistLaneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: LaneViewHolder, position: Int) = holder.bind(getItem(position))

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
