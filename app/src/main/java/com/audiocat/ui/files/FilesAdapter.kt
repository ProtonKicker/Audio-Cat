package com.audiocat.ui.files

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.R
import com.audiocat.databinding.ItemTrackBinding
import java.io.File

/**
 * Renders [FileRow]s in the Files tab.
 *
 * Selection lives in the fragment (the single source of truth) and is mirrored
 * here through [render], so entering selection mode or ticking one row only
 * rebinds the rows that are on screen.
 *
 * @param artworkOf resolves the cached art file for a row; called per bind so
 *   the list never stats thousands of files up front.
 */
class FilesAdapter(
    private val artworkOf: (FileRow) -> File?,
    private val onRowClick: (FileRow) -> Unit,
    private val onRowLongClick: (FileRow) -> Unit,
    private val onMoreClick: (FileRow) -> Unit,
) : ListAdapter<FileRow, FilesAdapter.RowHolder>(DIFF) {

    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()

    /** Mirrors the fragment's selection state into the visible rows. */
    fun render(selectionMode: Boolean, selectedPaths: Set<String>) {
        if (this.selectionMode == selectionMode && this.selectedPaths == selectedPaths) return
        this.selectionMode = selectionMode
        this.selectedPaths = selectedPaths
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(getItem(position))

    inner class RowHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: FileRow) {
            binding.rowTitle.text = row.title
            binding.rowSubtitle.text = row.subtitle
            binding.rowDuration.text = row.duration
            binding.rowVideoBadge.isVisible = row.track.isVideo
            binding.rowUnlisted.isVisible = row.track.unlisted
            binding.rowCheck.isVisible = selectionMode
            binding.rowCheck.isChecked = row.path in selectedPaths
            // Inside selection mode a tap already toggles the row, so the
            // overflow menu would only get in the way.
            binding.rowMore.isVisible = !selectionMode

            val art = artworkOf(row)
            if (art == null) {
                binding.rowArt.setImageResource(R.drawable.art_default)
            } else {
                binding.rowArt.setImageURI(Uri.fromFile(art))
            }

            binding.rowRoot.setOnClickListener { onRowClick(row) }
            binding.rowRoot.setOnLongClickListener {
                onRowLongClick(row)
                true
            }
            binding.rowMore.setOnClickListener { onMoreClick(row) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<FileRow>() {
            override fun areItemsTheSame(oldItem: FileRow, newItem: FileRow): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: FileRow, newItem: FileRow): Boolean =
                oldItem == newItem
        }
    }
}
