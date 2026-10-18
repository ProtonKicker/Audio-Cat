package com.audiocat.ui.playing

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.R
import com.audiocat.data.db.Playlist
import com.audiocat.databinding.ItemPlaylistBinding

/** A playlist together with its track count, as rendered by the list. */
data class PlaylistRow(val playlist: Playlist, val trackCount: Int)

/**
 * Playlist list, shared by the playlists sheet and the "add to playlist" picker.
 *
 * [showDelete] is false in picker mode so the destructive affordance only
 * appears where it makes sense.
 */
class PlaylistRowAdapter(
    private val showDelete: Boolean,
    private val onClick: (Playlist) -> Unit,
    private val onDelete: ((Playlist) -> Unit)? = null,
) : RecyclerView.Adapter<PlaylistRowAdapter.PlaylistViewHolder>() {

    private val items = mutableListOf<PlaylistRow>()

    fun submitList(rows: List<PlaylistRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder =
        PlaylistViewHolder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) =
        holder.bind(items[position])

    inner class PlaylistViewHolder(
        private val binding: ItemPlaylistBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position].playlist)
            }
            binding.playlistDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onDelete?.invoke(items[position].playlist)
            }
        }

        fun bind(row: PlaylistRow) {
            binding.playlistName.text = row.playlist.name
            binding.playlistCount.text = trackCountLabel(binding.root.context, row.trackCount)
            binding.playlistDelete.visibility = if (showDelete) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
    }

    private companion object {
        fun trackCountLabel(context: Context, count: Int): String =
            context.resources.getQuantityString(R.plurals.playlist_track_count, count, count)
    }
}
