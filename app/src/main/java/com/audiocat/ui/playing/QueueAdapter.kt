package com.audiocat.ui.playing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.R
import com.audiocat.data.db.Track
import com.audiocat.databinding.ItemQueueBinding
import com.audiocat.ui.common.ArtworkLoader
import com.audiocat.ui.common.Formatters
import com.google.android.material.color.MaterialColors

/**
 * Queue rows with a drag handle.
 *
 * A plain [RecyclerView.Adapter] is used rather than a ListAdapter because the
 * queue is short and drag-and-drop needs stable positions more than diffing.
 */
class QueueAdapter(
    private val onClick: (position: Int) -> Unit,
    private val onRemove: (position: Int) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<Track>()
    private var playingPosition = -1

    /** Invoked when the user presses a row's drag handle. */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    fun submit(tracks: List<Track>, playingIndex: Int) {
        items.clear()
        items.addAll(tracks)
        playingPosition = playingIndex
        notifyDataSetChanged()
    }

    /**
     * Moves a row locally so the list follows the finger without waiting for the
     * service round-trip. The service emits the authoritative order afterwards.
     */
    fun moveLocally(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val previousPlaying = playingPosition
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
        playingPosition = when (playingPosition) {
            from -> to
            in (minOf(from, to) + 1)..maxOf(from, to) ->
                if (from < playingPosition) playingPosition - 1 else playingPosition + 1
            else -> playingPosition
        }
        if (playingPosition != previousPlaying) {
            if (previousPlaying in items.indices) notifyItemChanged(previousPlaying)
            if (playingPosition in items.indices) notifyItemChanged(playingPosition)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder =
        QueueViewHolder(ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) =
        holder.bind(items[position], position == playingPosition)

    inner class QueueViewHolder(
        private val binding: ItemQueueBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.queueRowRoot.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position)
            }
            binding.queueRemove.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onRemove(position)
            }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }

        fun bind(track: Track, isPlaying: Boolean) {
            val context = binding.root.context
            binding.queueTitle.text = track.title
            binding.queueTitle.setTextColor(
                MaterialColors.getColor(
                    binding.queueTitle,
                    if (isPlaying) {
                        com.google.android.material.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurface
                    },
                ),
            )
            binding.queueSubtitle.text = buildString {
                append(track.artist ?: context.getString(R.string.now_playing_unknown_artist))
                if (track.durationMs > 0) {
                    append(" · ")
                    append(Formatters.duration(track.durationMs))
                }
            }
            ArtworkLoader.load(binding.queueArt, track.artPath)
        }
    }
}
