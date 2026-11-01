package com.audiocat.ui.files

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiocat.AudioCatApp
import com.audiocat.MainActivity
import com.audiocat.R
import com.audiocat.data.db.FolderMode
import com.audiocat.data.db.Track
import com.audiocat.databinding.DialogTextInputBinding
import com.audiocat.databinding.FragmentFilesBinding
import com.audiocat.files.RenameOutcome
import com.audiocat.files.RenamePattern
import com.audiocat.files.RenamePlanner
import com.audiocat.files.RenameTarget
import com.audiocat.ui.common.FOLDER_UNAVAILABLE_MESSAGE
import com.audiocat.ui.common.Formatters
import com.audiocat.ui.playing.PlaylistPickerDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * File management tab.
 *
 * Lists every scanned file - listed or not, audio or video - with in-memory
 * chip filters, multi-select actions, and a per-file action sheet. Nothing here
 * plays audio: this screen only rearranges what the library knows about.
 */
class FilesFragment : Fragment(R.layout.fragment_files) {

    private var binding: FragmentFilesBinding? = null

    private val app get() = AudioCatApp.get()

    private lateinit var adapter: FilesAdapter

    /** Launches the write-consent prompt and retries a rename. */
    private lateinit var renamer: RenameRunner

    /** Every row from the database, before the chip filter is applied. */
    private var allRows: List<FileRow> = emptyList()

    /**
     * Multi-select chip state. "All" is the empty selection: it is reset to
     * checked whenever no specific chip is on, and any specific chip turning
     * on switches "All" off. Listed/Unlisted and Audio/Video are exclusive.
     * The chips themselves are the single source of truth for the filter.
     */
    private var suppressChipEvents = false
    private var chipAll: Chip? = null
    private var chipListed: Chip? = null
    private var chipUnlisted: Chip? = null
    private var chipAudio: Chip? = null
    private var chipVideo: Chip? = null

    private var selectionMode = false
    private val selectedPaths = linkedSetOf<String>()

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            // The page can be gone if the picker outlived the view.
            if (view == null) return@registerForActivityResult
            val path = Formatters.treeUriToPath(requireContext(), uri)
            if (path == null) {
                snack(FOLDER_UNAVAILABLE_MESSAGE)
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (app.scanCoordinator.addFolder(path, FolderMode.INCLUDE)) {
                        app.scanCoordinator.scan(force = false)
                    } else {
                        snack(FOLDER_UNAVAILABLE_MESSAGE)
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = FragmentFilesBinding.bind(view)
        binding = views

        adapter = FilesAdapter(
            artworkOf = { app.artwork.fileByName(it.track.artPath) },
            onRowClick = ::onRowClicked,
            onRowLongClick = ::onRowLongPressed,
            onMoreClick = ::showActions,
            onPlayClick = ::onPlayPressed,
        )
        views.filesList.layoutManager = LinearLayoutManager(requireContext())
        views.filesList.adapter = adapter
        views.filesList.setHasFixedSize(true)

        renamer = RenameRunner(this, viewLifecycleOwner.lifecycleScope, ::reportRename)

        buildFilters(views)
        wireSelectionBar(views)
        views.fabAddFolder.setOnClickListener { folderPicker.launch(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.trackDao().observeAll().collect { tracks ->
                        allRows = tracks.map(::FileRow)
                        submitFiltered()
                    }
                }
                launch {
                    combine(
                        app.database.trackDao().observeTotalSize(),
                        app.database.trackDao().observeTotalCount(),
                    ) { size, count -> size to count }
                        .collect { (size, count) ->
                            binding?.storageSummary?.text = resources.getQuantityString(
                                R.plurals.settings_storage,
                                count,
                                Formatters.fileSize(size),
                                count,
                            )
                        }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // List and filters
    // ---------------------------------------------------------------------

    private fun buildFilters(views: FragmentFilesBinding) {
        // "All" first so it is the initial (and fallback) selection. Every
        // chip keeps its own check state; the rules below enforce the valid
        // combinations: any specific chip turns All off, exclusivity holds
        // within each pair, and an empty selection snaps back to All.
        chipAll = addChip(views, FilesFilter.ALL).also { it.isChecked = true }
        chipListed = addChip(views, FilesFilter.LISTED)
        chipUnlisted = addChip(views, FilesFilter.UNLISTED)
        chipAudio = addChip(views, FilesFilter.AUDIO)
        chipVideo = addChip(views, FilesFilter.VIDEO)
    }

    private fun addChip(views: FragmentFilesBinding, kind: FilesFilter): Chip =
        Chip(requireContext()).apply {
            id = View.generateViewId()
            text = kind.label
            isCheckable = true
            setOnCheckedChangeListener { _, isChecked -> onFilterChipChanged(kind, isChecked) }
            views.filterChips.addView(this)
        }

    /** Enforces the valid chip combinations after any user toggle. */
    private fun onFilterChipChanged(changed: FilesFilter, isChecked: Boolean) {
        if (suppressChipEvents) return
        suppressChipEvents = true
        try {
            if (changed == FilesFilter.ALL) {
                // Selecting All clears every specific chip; unchecking All only
                // matters if nothing specific is left (All is re-selected then).
                if (isChecked) {
                    chipListed?.isChecked = false
                    chipUnlisted?.isChecked = false
                    chipAudio?.isChecked = false
                    chipVideo?.isChecked = false
                }
            } else {
                // Any specific choice leaves the All state.
                chipAll?.isChecked = false
                if (isChecked) {
                    // Exclusive pairs: picking one clears the other.
                    when (changed) {
                        FilesFilter.LISTED -> chipUnlisted?.isChecked = false
                        FilesFilter.UNLISTED -> chipListed?.isChecked = false
                        FilesFilter.AUDIO -> chipVideo?.isChecked = false
                        FilesFilter.VIDEO -> chipAudio?.isChecked = false
                        else -> Unit
                    }
                }
            }
            // A filter is always active: with no specific chip on, All returns.
            if (chipAll?.isChecked != true && specificChecked().isEmpty()) {
                chipAll?.isChecked = true
            }
        } finally {
            suppressChipEvents = false
        }
        submitFiltered()
    }

    /** The currently on specific chip types, if any (Listed/Unlisted/Audio/Video). */
    private fun specificChecked(): Set<FilesFilter> = buildSet {
        if (chipListed?.isChecked == true) add(FilesFilter.LISTED)
        if (chipUnlisted?.isChecked == true) add(FilesFilter.UNLISTED)
        if (chipAudio?.isChecked == true) add(FilesFilter.AUDIO)
        if (chipVideo?.isChecked == true) add(FilesFilter.VIDEO)
    }

    /** Applies the selected chips in a single pass and updates the empty state. */
    private fun submitFiltered() {
        val views = binding ?: return
        val active = specificChecked()
        val rows = allRows.filter { row ->
            val okListing = when {
                FilesFilter.LISTED in active -> !row.track.unlisted
                FilesFilter.UNLISTED in active -> row.track.unlisted
                else -> true
            }
            val okKind = when {
                FilesFilter.AUDIO in active -> !row.track.isVideo
                FilesFilter.VIDEO in active -> row.track.isVideo
                else -> true
            }
            okListing && okKind
        }
        adapter.submitList(rows)
        views.filesEmpty.isVisible = rows.isEmpty()
    }

    // ---------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------

    private fun wireSelectionBar(views: FragmentFilesBinding) {
        views.btnSelectionClose.setOnClickListener { exitSelection() }
        views.btnUnlist.setOnClickListener { setUnlisted(selectedPaths.toList(), unlisted = true) }
        views.btnRelist.setOnClickListener { setUnlisted(selectedPaths.toList(), unlisted = false) }
        views.btnAddToPlaylist.setOnClickListener {
            if (selectedPaths.isNotEmpty()) {
                PlaylistPickerDialog.newInstance(selectedPaths.toList())
                    .show(parentFragmentManager, PlaylistPickerDialog.TAG)
            }
        }
        views.btnBatchRename.setOnClickListener {
            if (selectedPaths.isNotEmpty()) {
                BatchRenameDialog.newInstance(selectedPaths.toList())
                    .show(parentFragmentManager, BatchRenameDialog.TAG)
            }
        }
        views.btnDelete.setOnClickListener { confirmDelete(selectedRows()) }
    }

    private fun onRowClicked(row: FileRow) {
        if (selectionMode) toggleSelection(row) else showActions(row)
    }

    /**
     * Quick-play from the Files tab: plays the currently filtered list from
     * [row] onward, matching the tap-to-play behaviour everywhere else.
     */
    private fun onPlayPressed(row: FileRow) {
        val rows = adapter.currentList
        val start = rows.indexOfFirst { it.path == row.path }.coerceAtLeast(0)
        val tracks = rows.map { it.track }
        if (tracks.isEmpty()) return
        val activity = requireActivity() as MainActivity
        activity.player.playTracks(tracks, start)
        activity.showPlayer()
    }

    private fun onRowLongPressed(row: FileRow) {
        if (selectionMode) {
            toggleSelection(row)
        } else {
            selectionMode = true
            selectedPaths += row.path
            renderSelection()
        }
    }

    private fun toggleSelection(row: FileRow) {
        if (!selectedPaths.remove(row.path)) selectedPaths += row.path
        if (selectedPaths.isEmpty()) exitSelection() else renderSelection()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPaths.clear()
        renderSelection()
    }

    private fun renderSelection() {
        val views = binding ?: return
        views.selectionBar.isVisible = selectionMode
        views.selectionCount.text = getString(R.string.files_selected, selectedPaths.size)
        // The "add folder" FAB is hidden (see its layout); it only ever toggled
        // visibility around the selection bar, whose logic is kept for reuse.
        adapter.render(selectionMode, selectedPaths.toSet())
    }

    /** Selected rows across every filter, so a filter change cannot hide work. */
    private fun selectedRows(): List<FileRow> = allRows.filter { it.path in selectedPaths }

    // ---------------------------------------------------------------------
    // Per-file actions
    // ---------------------------------------------------------------------

    private fun showActions(row: FileRow) {
        val labels = arrayOf(
            getString(R.string.files_add_playlist),
            getString(R.string.files_info),
            getString(R.string.files_rename),
            getString(if (row.track.unlisted) R.string.files_relist else R.string.files_unlist),
            getString(R.string.files_delete),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(row.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> PlaylistPickerDialog.newInstance(listOf(row.path))
                        .show(parentFragmentManager, PlaylistPickerDialog.TAG)
                    1 -> showInfo(row)
                    2 -> promptRename(row)
                    3 -> setUnlisted(listOf(row.path), unlisted = !row.track.unlisted)
                    else -> confirmDelete(listOf(row))
                }
            }
            .show()
    }

    private fun showInfo(row: FileRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.files_info)
            .setMessage(infoText(row.track))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    /** Field labels are not string resources; the values are the useful part. */
    private fun infoText(track: Track): String = buildString {
        appendLine("Path: ${track.path}")
        appendLine("Size: ${Formatters.fileSize(track.fileSize)}")
        appendLine("Duration: ${Formatters.duration(track.durationMs)}")
        appendLine("Type: ${track.mimeType ?: "unknown"}")
        appendLine("Sample rate: ${Formatters.frequency(track.sampleRate)}")
        appendLine("Bit rate: ${bitRateLabel(track.bitRate)}")
        appendLine("Channels: ${track.channelCount}")
        append("Folder: ${track.folder ?: track.path.substringBeforeLast('/', "")}")
    }

    private fun bitRateLabel(bitRate: Int): String =
        if (bitRate <= 0) "—" else "${bitRate / 1000} kbps"

    private fun promptRename(row: FileRow) {
        val views = DialogTextInputBinding.inflate(layoutInflater)
        views.inputName.setText(row.track.displayTitle)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.files_rename)
            .setView(views.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = views.inputName.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) rename(row, name)
            }
            .show()
    }

    /**
     * Renames one file.
     *
     * The planner is reused with a single target tagged mode: that keeps the
     * extension, sanitises the name, and gives the same consent handling as a
     * batch rename.
     */
    private fun rename(row: FileRow, newName: String) {
        // The planner appends the original extension, so a typed extension is
        // dropped first instead of ending up doubled.
        val extension = row.path.substringAfterLast('.', "")
        val stem = if (extension.isEmpty()) newName else newName.removeSuffix(".$extension")
        val plan = RenamePlanner.plan(
            listOf(RenameTarget(path = row.path, title = stem, artist = null)),
            RenamePattern(useTags = true),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val error = RenamePlanner.validate(plan, app.fileOperations.existingPathsFor(listOf(row.path)))
            if (error != null) {
                snack(error)
            } else {
                renamer.apply(plan)
            }
        }
    }

    private fun reportRename(outcome: RenameOutcome) {
        when {
            outcome.failures.isNotEmpty() ->
                snack(getString(R.string.rename_failed, outcome.failures.size))
            outcome.renamed > 0 ->
                snack(getString(R.string.rename_applied, outcome.renamed))
        }
    }

    private fun setUnlisted(paths: List<String>, unlisted: Boolean) {
        if (paths.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.trackDao().setUnlisted(paths, unlisted)
        }
    }

    private fun confirmDelete(rows: List<FileRow>) {
        if (rows.isEmpty()) return
        val message = if (rows.size == 1) {
            getString(R.string.files_delete_confirm_single, rows.first().title)
        } else {
            getString(R.string.files_delete_confirm, rows.size)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.files_delete)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteFiles(rows) }
            .show()
    }

    private fun deleteFiles(rows: List<FileRow>) {
        val paths = rows.map { it.path }
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = app.fileOperations.deleteFiles(paths)
            exitSelection()
            // Delete feedback has no string resource; the count is the message.
            snack(
                if (outcome.failures.isEmpty()) {
                    "Deleted ${outcome.renamed} file(s)"
                } else {
                    "${outcome.failures.size} file(s) could not be deleted"
                },
            )
        }
    }

    private fun snack(message: String) {
        binding?.let { Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show() }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

/**
 * Chip filters for the file list.
 *
 * These five labels have no matching string resources, and the list is small
 * enough for in-memory filtering.
 */
private enum class FilesFilter(val label: String) {
    ALL("All"),
    LISTED("Listed"),
    UNLISTED("Unlisted"),
    AUDIO("Audio"),
    VIDEO("Video"),
}
