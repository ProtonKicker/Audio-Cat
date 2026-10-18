package com.audiocat.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.AudioCatApp
import com.audiocat.BuildConfig
import com.audiocat.R
import com.audiocat.data.AppSettings
import com.audiocat.data.NewFileBehaviour
import com.audiocat.data.RepeatMode
import com.audiocat.data.ThemeMode
import com.audiocat.data.db.FolderMode
import com.audiocat.data.db.ScanFolder
import com.audiocat.data.db.TrackSort
import com.audiocat.databinding.FragmentSettingsBinding
import com.audiocat.databinding.ItemSettingsRowBinding
import com.audiocat.playback.PlaybackService
import com.audiocat.scanner.ScanStatus
import com.audiocat.scanner.summary
import com.audiocat.ui.common.FOLDER_UNAVAILABLE_MESSAGE
import com.audiocat.ui.common.Formatters
import com.audiocat.ui.common.ThemeController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab.
 *
 * The screen is rendered from the [AppSettings] snapshot into a plain column,
 * so adding a preference is one [addRow] call and the whole screen can be
 * re-rendered after a reset. There is no per-control state kept anywhere else.
 */
@AndroidXOptIn(UnstableApi::class)
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var binding: FragmentSettingsBinding? = null

    private val app get() = AudioCatApp.get()

    private lateinit var folderAdapter: FolderAdapter

    /** The "last scan" row; rewritten in place while a scan is running. */
    private var scanRow: ItemSettingsRowBinding? = null

    private val settings: AppSettings get() = app.settings.current

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
        binding = FragmentSettingsBinding.bind(view)
        folderAdapter = FolderAdapter(
            onModeToggle = ::toggleFolderMode,
            onRemove = { folder ->
                viewLifecycleOwner.lifecycleScope.launch {
                    app.scanCoordinator.removeFolder(folder.id)
                }
            },
        )
        render()
        observeScanState()
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    /** Rebuilds every row from the current settings snapshot. */
    private fun render() {
        val views = binding ?: return
        val content = views.settingsContent
        // The XML contributes the screen title; only previously rendered rows
        // are dropped.
        while (content.childCount > 1) content.removeViewAt(content.childCount - 1)
        scanRow = null

        val current = settings

        // --- Scanning ---
        addSection(R.string.settings_scan)
        addRow(
            R.string.settings_auto_scan,
            summary = getString(R.string.settings_auto_scan_summary),
            switch = true,
        ).rowSwitch.apply {
            isChecked = current.autoScan
            setOnCheckedChangeListener { _, enabled ->
                update { it.copy(autoScan = enabled) }
                app.scanCoordinator.onAutoScanSettingChanged(enabled)
            }
        }
        addRow(R.string.settings_scan_now, icon = R.drawable.ic_refresh) {
            app.scanCoordinator.scan(force = false)
        }
        addRow(R.string.settings_full_rescan, icon = R.drawable.ic_storage) {
            app.scanCoordinator.scan(force = true)
        }
        scanRow = addRow(R.string.settings_last_scan).also {
            it.rowTitle.text =
                getString(R.string.settings_last_scan, Formatters.relativeTime(current.lastScanAt))
        }

        addSection(R.string.settings_scan_folders)
        addRow(R.string.settings_add_folder, icon = R.drawable.ic_folder, chevron = true) {
            folderPicker.launch(null)
        }
        content.addView(folderListView())

        // --- Library ---
        addSection(R.string.settings_library)
        addRow(
            R.string.settings_art_quality,
            value = ART_QUALITY_LABELS[current.artQuality.coerceIn(0, 2)],
            chevron = true,
        ) {
            chooseFrom(
                title = R.string.settings_art_quality,
                labels = ART_QUALITY_LABELS,
                checkedIndex = current.artQuality.coerceIn(0, 2),
            ) { index -> update { it.copy(artQuality = index) } }
        }
        addRow(
            R.string.settings_new_files,
            value = newFileLabel(current.newFileBehaviour),
            chevron = true,
        ) {
            chooseFrom(
                title = R.string.settings_new_files,
                labels = NewFileBehaviour.entries.map(::newFileLabel).toTypedArray(),
                checkedIndex = NewFileBehaviour.entries.indexOf(current.newFileBehaviour),
            ) { index ->
                update { it.copy(newFileBehaviour = NewFileBehaviour.entries[index]) }
            }
        }
        addRow(
            R.string.settings_default_sort,
            value = sortLabel(TrackSort.fromKey(current.defaultSort)),
            chevron = true,
        ) {
            chooseFrom(
                title = R.string.settings_default_sort,
                labels = TrackSort.entries.map(::sortLabel).toTypedArray(),
                checkedIndex = TrackSort.entries.indexOf(TrackSort.fromKey(current.defaultSort)),
            ) { index -> update { it.copy(defaultSort = TrackSort.entries[index].name) } }
        }

        // --- Playback ---
        addSection(R.string.settings_playback)
        addRow(R.string.settings_shuffle_default, switch = true).rowSwitch.apply {
            isChecked = current.shuffleByDefault
            setOnCheckedChangeListener { _, enabled ->
                update { it.copy(shuffleByDefault = enabled) }
            }
        }
        val crossfadeIndex = CROSSFADE_OPTIONS.indexOf(current.crossfadeMs).coerceAtLeast(0)
        addRow(
            R.string.settings_repeat_default,
            value = repeatLabel(current.repeatMode),
            chevron = true,
        ) {
            chooseFrom(
                title = R.string.settings_repeat_default,
                labels = RepeatMode.entries.map(::repeatLabel).toTypedArray(),
                checkedIndex = RepeatMode.entries.indexOf(current.repeatMode),
            ) { index ->
                // update() pushes the new mode to a live player through the
                // service's applySettings.
                update { it.copy(repeatMode = RepeatMode.entries[index]) }
            }
        }
        addRow(
            R.string.settings_crossfade,
            value = crossfadeLabel(CROSSFADE_OPTIONS[crossfadeIndex]),
            chevron = true,
        ) {
            chooseFrom(
                title = R.string.settings_crossfade,
                labels = CROSSFADE_OPTIONS.map(::crossfadeLabel).toTypedArray(),
                checkedIndex = crossfadeIndex,
            ) { index -> update { it.copy(crossfadeMs = CROSSFADE_OPTIONS[index]) } }
        }
        addRow(R.string.settings_resume_headphones, switch = true).rowSwitch.apply {
            isChecked = current.resumeOnHeadphones
            setOnCheckedChangeListener { _, enabled ->
                update { it.copy(resumeOnHeadphones = enabled) }
            }
        }
        addRow(R.string.settings_pause_disconnect, switch = true).rowSwitch.apply {
            isChecked = current.pauseOnHeadphonesDisconnect
            setOnCheckedChangeListener { _, enabled ->
                update { it.copy(pauseOnHeadphonesDisconnect = enabled) }
            }
        }

        // --- Appearance ---
        addSection(R.string.settings_appearance)
        addRow(R.string.settings_theme, value = themeLabel(current.themeMode), chevron = true) {
            chooseFrom(
                title = R.string.settings_theme,
                labels = ThemeMode.entries.map(::themeLabel).toTypedArray(),
                checkedIndex = ThemeMode.entries.indexOf(current.themeMode),
            ) { index ->
                val mode = ThemeMode.entries[index]
                update { it.copy(themeMode = mode) }
                ThemeController.apply(mode)
                // The delegate's implicit activity recreation can be swallowed
                // while the dialog window is still closing (seen on EMUI);
                // recreating explicitly applies the theme immediately.
                requireActivity().recreate()
            }
        }

        // --- Advanced ---
        addSection(R.string.settings_advanced)
        addRow(R.string.settings_version, value = BuildConfig.VERSION_NAME)
        addRow(
            R.string.settings_clear_art,
            summary = getString(R.string.settings_art_cleared_note),
        ) { clearArtwork() }
        addRow(R.string.settings_reset) { confirmReset() }

        // --- Privacy ---
        addSection(R.string.settings_privacy)
        content.addView(privacyBody())
    }

    private fun addSection(@StringRes title: Int) {
        val views = binding ?: return
        views.settingsContent.addView(
            TextView(requireContext()).apply {
                setText(title)
                setTextAppearance(R.style.TextAppearance_AudioCat_SectionHeader)
                setPadding(0, dp(20), 0, dp(4))
            },
        )
    }

    /**
     * Inflates one settings row and appends it.
     *
     * Every control is optional; switches are returned unbound so the caller can
     * set the initial state before attaching its listener (which would otherwise
     * fire on render). The whole row is the touch target for a switch: a 56dp
     * row that only responds inside a 24dp thumb is unreadable on a phone, so the
     * click delegates to [android.widget.CompoundButton.toggle] and the listener
     * the caller installs does the persisting.
     */
    private fun addRow(
        @StringRes title: Int,
        summary: String? = null,
        @DrawableRes icon: Int? = null,
        switch: Boolean = false,
        value: String? = null,
        chevron: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): ItemSettingsRowBinding {
        val views = checkNotNull(binding) { "settings row added with no view" }
        val row = ItemSettingsRowBinding.inflate(layoutInflater, views.settingsContent, false)
        row.rowTitle.setText(title)
        row.rowSummary.text = summary
        row.rowSummary.isVisible = summary != null
        if (icon == null) {
            row.rowIcon.isVisible = false
        } else {
            row.rowIcon.setImageResource(icon)
            row.rowIcon.isVisible = true
        }
        row.rowSwitch.isVisible = switch
        row.rowValue.text = value
        row.rowValue.isVisible = value != null
        row.rowChevron.isVisible = chevron
        when {
            switch -> row.rowRoot.setOnClickListener { row.rowSwitch.toggle() }
            onClick != null -> row.rowRoot.setOnClickListener { onClick() }
        }
        views.settingsContent.addView(row.root)
        return row
    }

    private fun folderListView(): RecyclerView = RecyclerView(requireContext()).apply {
        layoutManager = LinearLayoutManager(requireContext())
        adapter = folderAdapter
        // The settings column keeps the scroll; this list only grows in place.
        isNestedScrollingEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun privacyBody(): TextView = TextView(requireContext()).apply {
        setText(R.string.settings_privacy_summary)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setPadding(0, 0, 0, dp(8))
    }

    // ---------------------------------------------------------------------
    // Dialogs and side effects
    // ---------------------------------------------------------------------

    /** Single-choice list shared by every "pick one of N" setting. */
    private fun chooseFrom(
        @StringRes title: Int,
        labels: Array<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit,
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(labels) { _, which ->
                onPick(which)
                // The row's value text is derived from settings, so re-render.
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearArtwork() {
        viewLifecycleOwner.lifecycleScope.launch {
            // A cache sweep touches thousands of small files: never on main.
            val removed = withContext(Dispatchers.IO) { app.artwork.clear() }
            binding?.let {
                Snackbar.make(
                    it.root,
                    getString(R.string.settings_art_cleared, removed),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_reset) { _, _ ->
                app.settings.reset()
                pushSettingsToPlayer()
                render()
            }
            .show()
    }

    private fun toggleFolderMode(folder: ScanFolder) {
        val next = if (folder.mode == FolderMode.EXCLUDE) FolderMode.INCLUDE else FolderMode.EXCLUDE
        viewLifecycleOwner.lifecycleScope.launch { app.scanCoordinator.setFolderMode(folder, next) }
    }

    /** Persists an edit and lets a running player pick it up immediately. */
    private fun update(transform: (AppSettings) -> AppSettings) {
        app.settings.update(transform)
        pushSettingsToPlayer()
    }

    /**
     * The single path from a settings change to the playback service.
     *
     * `applySettings` re-applies repeat, speed, pitch, effects and crossfade, so
     * no caller needs to reach into the service directly.
     */
    private fun pushSettingsToPlayer() {
        PlaybackService.peek()?.applySettings(app.settings.current)
    }

    private fun observeScanState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.scanCoordinator.status.collect(::updateScanRow)
                }
                launch {
                    app.scanCoordinator.folders.collect { folderAdapter.submitList(it) }
                }
            }
        }
    }

    private fun updateScanRow(status: ScanStatus) {
        val row = scanRow ?: return
        row.rowTitle.text = if (status.running) {
            getString(R.string.settings_scanning, status.scanned, status.total)
        } else {
            getString(R.string.settings_last_scan, Formatters.relativeTime(settings.lastScanAt))
        }
        // The counts are the only evidence a user gets that a "full refresh"
        // really re-read the library instead of skipping every unchanged file.
        val detail = status.lastError ?: status.lastResult?.summary()
        row.rowSummary.text = detail.orEmpty()
        row.rowSummary.isVisible = detail != null
    }

    private fun snack(message: String) {
        binding?.let { Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------
    // Labels for values that have no string resource of their own
    // ---------------------------------------------------------------------

    private fun newFileLabel(behaviour: NewFileBehaviour): String = when (behaviour) {
        NewFileBehaviour.ADD_SILENTLY -> "Add silently"
        NewFileBehaviour.ADD_AND_NOTIFY -> "Add and notify"
        NewFileBehaviour.UNLIST -> "Keep unlisted"
    }

    private fun sortLabel(sort: TrackSort): String = when (sort) {
        TrackSort.DATE_ADDED_DESC -> "Date added"
        TrackSort.TITLE_ASC -> "Title"
        TrackSort.ARTIST_ASC -> "Artist"
        TrackSort.DURATION_DESC -> "Duration"
        TrackSort.SIZE_DESC -> "File size"
    }

    private fun repeatLabel(mode: RepeatMode): String = getString(
        when (mode) {
            RepeatMode.OFF -> R.string.repeat_off
            RepeatMode.ALL -> R.string.repeat_all
            RepeatMode.ONE -> R.string.action_repeat_one
        },
    )

    private fun crossfadeLabel(ms: Int): String =
        if (ms == 0) getString(R.string.settings_crossfade_off) else "${ms / 1000}s"

    private fun themeLabel(mode: ThemeMode): String = getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.settings_theme_system
            ThemeMode.LIGHT -> R.string.settings_theme_light
            ThemeMode.DARK -> R.string.settings_theme_dark
        },
    )

    override fun onDestroyView() {
        scanRow = null
        binding = null
        super.onDestroyView()
    }

    private companion object {
        val ART_QUALITY_LABELS = arrayOf("Small", "Medium", "Large")
        val CROSSFADE_OPTIONS = intArrayOf(0, 2_000, 4_000, 8_000, 12_000)
    }
}
