package com.audiocat.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.data.db.FolderMode
import com.audiocat.data.db.ScanFolder
import com.audiocat.databinding.ItemFolderBinding

/**
 * Renders the scan configuration.
 *
 * Included and excluded folders share one list because they are the same
 * entity: the chip shows the mode and toggles it, so there is exactly one place
 * where a folder is drawn.
 */
class FolderAdapter(
    private val onModeToggle: (ScanFolder) -> Unit,
    private val onRemove: (ScanFolder) -> Unit,
) : ListAdapter<ScanFolder, FolderAdapter.FolderHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderHolder =
        FolderHolder(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: FolderHolder, position: Int) = holder.bind(getItem(position))

    inner class FolderHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: ScanFolder) {
            binding.folderPath.text = folder.path.ifBlank { ALL_VOLUMES_LABEL }
            binding.folderCount.text = countLabel(folder.fileCount)
            binding.folderModeChip.apply {
                val excluded = folder.mode == FolderMode.EXCLUDE
                text = if (excluded) EXCLUDE_LABEL else INCLUDE_LABEL
                isChecked = excluded
                setOnClickListener { onModeToggle(folder) }
            }
            binding.folderRemove.setOnClickListener { onRemove(folder) }
        }
    }

    private companion object {
        const val ALL_VOLUMES_LABEL = "All storage"
        const val INCLUDE_LABEL = "Include"
        const val EXCLUDE_LABEL = "Exclude"

        fun countLabel(count: Int): String = if (count == 1) "1 file" else "$count files"

        val DIFF = object : DiffUtil.ItemCallback<ScanFolder>() {
            override fun areItemsTheSame(oldItem: ScanFolder, newItem: ScanFolder): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ScanFolder, newItem: ScanFolder): Boolean =
                oldItem == newItem
        }
    }
}
