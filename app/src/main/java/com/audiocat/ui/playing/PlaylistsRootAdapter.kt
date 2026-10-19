package com.audiocat.ui.playing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.R
import com.audiocat.databinding.ItemPlaylistBinding

/** A row in the root playlists view: either the virtual "All" or a real playlist. */
sealed class PlaylistsRootEntry {

    abstract val id: String
    abstract val title: String

    /** The virtual "All" playlist — always present, contains every detectable song. */
    data class All(val trackCount: Int) : PlaylistsRootEntry() {
        override val id: String = "all"
        override val title: String
            get() = "All"
    }

    /** A user-created playlist with its track count. */
    data class Playlist(val row: PlaylistRow) : PlaylistsRootEntry() {
        override val id: String = "pl_${row.playlist.id}"
        override val title: String
            get() = row.playlist.name

        fun trackCount(): Int = row.trackCount
    }
}

/**
 * Root-level playlists list: a virtual "All" entry leads, followed by the user's
 * playlists. Used by the playlist tab's default view.
 */
class PlaylistsRootAdapter(
    private val onClick: (PlaylistsRootEntry) -> Unit,
    private val onDelete: (PlaylistsRootEntry) -> Unit,
) : ListAdapter<PlaylistsRootEntry, PlaylistsRootAdapter.RootViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RootViewHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return RootViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RootViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class RootViewHolder(
        private val binding: ItemPlaylistBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
            binding.playlistDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onDelete(getItem(position))
            }
        }

        fun bind(entry: PlaylistsRootEntry) {
            binding.playlistName.text = entry.title
            val count = when (entry) {
                is PlaylistsRootEntry.All -> entry.trackCount
                is PlaylistsRootEntry.Playlist -> entry.trackCount()
            }
            binding.playlistCount.text = binding.root.context.resources.getQuantityString(
                R.plurals.playlist_track_count, count, count,
            )
            // The virtual "All" cannot be deleted.
            binding.playlistDelete.visibility =
                if (entry is PlaylistsRootEntry.Playlist) android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PlaylistsRootEntry>() {
            override fun areItemsTheSame(
                oldItem: PlaylistsRootEntry,
                newItem: PlaylistsRootEntry,
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: PlaylistsRootEntry,
                newItem: PlaylistsRootEntry,
            ): Boolean = oldItem == newItem
        }
    }
}
