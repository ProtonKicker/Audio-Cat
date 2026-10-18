package com.audiocat.ui.playing

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.R
import com.audiocat.data.db.Track
import com.audiocat.databinding.ItemTrackBinding
import com.audiocat.ui.common.Formatters

/**
 * Library list adapter.
 *
 * Extends [ListAdapter] so that re-querying the library diffs on a background
 * thread and only rebinds changed rows; a plain adapter would rebind every row
 * on each database emission.
 *
 * The same adapter serves the library, playlist detail and search results so
 * every list in the app behaves identically.
 *
 * Two gestures, distinct meanings: the row does whatever its host's primary
 * action is, the dots open the per-track actions, and a long press belongs to
 * the host (selection, dragging) rather than to the menu.
 */
class TrackAdapter(
    private val artwork: (String?) -> Uri?,
    private val onClick: (position: Int, track: Track) -> Unit,
    private val onLongClick: (position: Int, track: Track) -> Unit = { _, _ -> },
    private val onMoreClick: (position: Int, track: Track) -> Unit = { _, _ -> },
) : ListAdapter<Track, TrackAdapter.TrackViewHolder>(DIFF) {

    /** Track currently playing, highlighted in the list. */
    private var playingPath: String? = null

    /** Optional secondary line; defaults to the artist. */
    private var subtitle: ((Track) -> String)? = null

    /** Selection state, driven by screens that support batch operations. */
    private var selectionMode = false
    private val selectedPaths = mutableSetOf<String>()

    private var showMore = false

    fun setPlayingPath(path: String?) {
        if (playingPath == path) return
        val previous = playingPath
        playingPath = path
        // Only the two affected rows need repainting.
        notifyRowFor(previous)
        notifyRowFor(path)
    }

    private fun notifyRowFor(path: String?) {
        if (path == null) return
        val index = currentList.indexOfFirst { it.path == path }
        if (index >= 0) notifyItemChanged(index)
    }

    fun setSubtitleProvider(provider: ((Track) -> String)?) {
        subtitle = provider
        notifyItemRangeChanged(0, itemCount)
    }

    /** Shows or hides the overflow affordance on every row. */
    fun setShowMore(visible: Boolean) {
        if (showMore == visible) return
        showMore = visible
        notifyItemRangeChanged(0, itemCount)
    }

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedPaths.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun setSelectedPaths(paths: Set<String>) {
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder =
        TrackViewHolder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class TrackViewHolder(
        private val binding: ItemTrackBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.rowRoot.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position, getItem(position))
            }
            binding.rowRoot.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    false
                } else {
                    onLongClick(position, getItem(position))
                    true
                }
            }
            binding.rowMore.setOnClickListener { openMore() }
            // The dots sit inside the row, so a long press that starts on them must
            // open the menu instead of falling through to the row's own gesture.
            binding.rowMore.setOnLongClickListener {
                openMore()
                true
            }
        }

        private fun openMore() {
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onMoreClick(position, getItem(position))
        }

        fun bind(track: Track) {
            val context = binding.root.context
            binding.rowTitle.text = track.title

            binding.rowSubtitle.text = subtitle?.invoke(track)
                ?: track.artist?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.now_playing_unknown_artist)

            binding.rowDuration.text =
                if (track.durationMs > 0) Formatters.duration(track.durationMs) else ""

            val playing = track.path == playingPath
            // Playing reads by ink weight, not hue: colour-blind safe and
            // stable across themes.
            binding.rowTitle.setTextColor(
                ContextCompat.getColor(context, if (playing) R.color.ink else R.color.ink_soft),
            )

            binding.rowUnlisted.visibility = if (track.unlisted) View.VISIBLE else View.GONE
            // Video containers are badged so the audio-only behaviour is obvious.
            binding.rowVideoBadge.visibility = if (track.isVideo) View.VISIBLE else View.GONE
            binding.rowCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.rowCheck.isChecked = selectedPaths.contains(track.path)
            binding.rowMore.visibility = if (showMore) View.VISIBLE else View.GONE

            val uri = artwork(track.artPath)
            if (uri != null) {
                binding.rowArt.setImageURI(uri)
            } else {
                binding.rowArt.setImageResource(R.drawable.art_default)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
        }
    }
}
