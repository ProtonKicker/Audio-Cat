package com.audiocat.ui.playing

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.audiocat.AudioCatApp
import com.audiocat.MainActivity
import com.audiocat.R
import com.audiocat.databinding.ItemSettingsRowBinding
import com.audiocat.databinding.SheetCoverBinding
import com.audiocat.scanner.ArtworkStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cover sheet: choose, replace or remove the picture shown for one track.
 *
 * A picked image is copied into the app's own storage at the moment it is chosen,
 * so deleting the original from the gallery afterwards changes nothing here - a
 * gallery uri is only ever a transient handle. Removing a custom cover puts the
 * track back into the extraction queue, which is how whatever the file itself
 * carries returns.
 */
class CoverSheet : BottomSheetDialogFragment() {

    private var binding: SheetCoverBinding? = null
    private var removeRow: ItemSettingsRowBinding? = null

    private val app get() = AudioCatApp.get()

    private val path: String? get() = arguments?.getString(ARG_PATH)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = path
        if (uri == null || target == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch { applyPicked(target, uri) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SheetCoverBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (path == null) {
            dismiss()
            return
        }
        val views = binding ?: return
        views.coverTitle.setText(R.string.cover_sheet_title)

        addRow(R.string.cover_choose, icon = R.drawable.ic_folder).rowRoot.setOnClickListener {
            pickImage.launch("image/*")
        }
        removeRow = addRow(R.string.cover_remove, icon = R.drawable.ic_close).also {
            it.rowRoot.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { removeCustom() } }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val track = withContext(Dispatchers.IO) { path?.let { app.database.trackDao().byPath(it) } }
                showSource(custom = app.artwork.isUserOwned(track?.artPath), hasArt = track?.artPath != null)
            }
        }
    }

    private fun addRow(title: Int, icon: Int): ItemSettingsRowBinding {
        val views = checkNotNull(binding) { "cover row added with no view" }
        val row = ItemSettingsRowBinding.inflate(layoutInflater, views.coverRows, false)
        row.rowTitle.setText(title)
        row.rowIcon.setImageResource(icon)
        row.rowIcon.isVisible = true
        views.coverRows.addView(row.root)
        return row
    }

    private fun showSource(custom: Boolean, hasArt: Boolean) {
        val views = binding ?: return
        views.coverSummary.setText(
            when {
                custom -> R.string.cover_source_user
                hasArt -> R.string.cover_source_file
                else -> R.string.cover_source_none
            },
        )
        removeRow?.rowRoot?.apply {
            isEnabled = custom
            alpha = if (custom) 1f else DISABLED_ALPHA
        }
    }

    private suspend fun applyPicked(target: String, uri: Uri) {
        val maxPx = ArtworkStore.maxPxForQuality(app.settings.current.artQuality)
        val stored = withContext(Dispatchers.IO) { app.artwork.putUser(target, uri, maxPx) }
        if (stored == null) {
            binding?.root?.let {
                Snackbar.make(it, R.string.cover_failed, Snackbar.LENGTH_LONG).show()
            }
            return
        }
        withContext(Dispatchers.IO) { app.database.trackDao().setArtPath(target, stored) }
        showSource(custom = true, hasArt = true)
        refreshPlayer()
    }

    /** Drops the user's own copy and lets extraction have another go. */
    private suspend fun removeCustom() {
        val target = path
        val track = target?.let { withContext(Dispatchers.IO) { app.database.trackDao().byPath(it) } }
        if (track == null || !app.artwork.isUserOwned(track.artPath)) return

        app.artwork.deleteUser(track.artPath)
        withContext(Dispatchers.IO) {
            app.database.trackDao().setArtPath(target, null)
            app.database.trackDao().requeueEnrichment(target)
        }
        showSource(custom = false, hasArt = false)
        refreshPlayer()
        app.scanCoordinator.scan(force = false) { refreshPlayer() }
    }

    /**
     * The player binds its metadata from the library row, so a cover that changed
     * outside playback has to be re-read rather than waited for.
     */
    private fun refreshPlayer() {
        (activity as? MainActivity)?.player?.refreshCurrentTrack()
    }

    override fun onDestroyView() {
        binding = null
        removeRow = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "CoverSheet"
        private const val ARG_PATH = "path"
        private const val DISABLED_ALPHA = 0.38f

        fun newInstance(path: String): CoverSheet = CoverSheet().apply {
            arguments = Bundle().apply { putString(ARG_PATH, path) }
        }
    }
}
