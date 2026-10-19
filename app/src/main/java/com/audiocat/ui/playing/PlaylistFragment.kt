package com.audiocat.ui.playing

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.AudioCatApp
import com.audiocat.MainActivity
import com.audiocat.R
import com.audiocat.data.db.Playlist
import com.audiocat.data.db.Track
import com.audiocat.data.db.TrackSort
import com.audiocat.databinding.DialogTextInputBinding
import com.audiocat.databinding.FragmentPlaylistBinding
import com.audiocat.playback.PlayerConnection
import com.audiocat.ui.common.ArtworkLoader
import com.audiocat.ui.common.Formatters
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Playlist tab: a vertical list of playlists, with a virtual "All" entry at the
 * top that contains every detectable song on the device. Clicking any entry
 * opens its song list. When inside a playlist the header shows a back button and
 * the search / sort / filter controls.
 *
 * The horizontal lane is gone — adding songs to a playlist lives in the Files
 * tab instead. Everything is Flow-driven so playlists appear as they are created.
 */
class PlaylistFragment : Fragment(R.layout.fragment_playlist) {

    private var binding: FragmentPlaylistBinding? = null

    /** The song list shown when inside a playlist. */
    private var tracksAdapter: TrackAdapter? = null

    /** The playlists list shown at root level. */
    private var rootAdapter: PlaylistsRootAdapter? = null

    /** Snapshot for header naming. */
    private var playlistNames: Map<Long, String> = emptyMap()

    /** Total track count for the virtual "All" entry. */
    private var totalTrackCount: Int = 0

    private val app get() = AudioCatApp.get()

    private val player: PlayerConnection
        get() = (requireActivity() as MainActivity).player

    /** Null = root level (showing playlists); non-null = inside a playlist. */
    private val openPlaylist = MutableStateFlow<PlaylistTarget?>(null)

    /** Live search text; empty means "show everything". */
    private val query = MutableStateFlow("")

    /** Sort order, seeded from settings. */
    private val sort = MutableStateFlow(TrackSort.DATE_ADDED_DESC)

    /** Format narrowing. */
    private val filter: MutableStateFlow<TrackFilter> = MutableStateFlow(TrackFilter.All)

    /** Snapshot for format filter dialog. */
    private var currentTracks: List<Track> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentPlaylistBinding.bind(view).also { this.binding = it }

        sort.value = TrackSort.fromKey(AudioCatApp.get().settings.current.defaultSort)

        setUpPlaylistsRoot(binding)
        setUpTrackList(binding)
        setUpHeader(binding)
        observePlaylists(binding)
        observeOpenPlaylist(binding)
        observePlayback()
        setUpBackHandling()
    }

    /**
     * While a playlist is open, the system back gesture / button should step back
     * to the playlists root rather than leaving the app entirely. At root the
     * default behaviour (exit) applies, so the callback only activates when it
     * has somewhere to go.
     */
    private fun setUpBackHandling() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                openPlaylist.value = null
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        // Enable the callback only when we are inside a playlist.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                openPlaylist.collect { target -> callback.isEnabled = target != null }
            }
        }
    }

    /** Hosts the virtual "All" plus the user's playlists. */
    private fun setUpPlaylistsRoot(binding: FragmentPlaylistBinding) {
        rootAdapter = PlaylistsRootAdapter(
            onClick = { entry -> openEntry(entry) },
            onDelete = { entry ->
                val row = (entry as? PlaylistsRootEntry.Playlist)?.row ?: return@PlaylistsRootAdapter
                confirmDelete(row.playlist)
            },
        ).also {
            binding.playlistsList.layoutManager = LinearLayoutManager(requireContext())
            binding.playlistsList.adapter = it
        }
    }

    private fun setUpTrackList(binding: FragmentPlaylistBinding) {
        tracksAdapter = TrackAdapter(
            artwork = ArtworkLoader::uri,
            onClick = { position, _ ->
                val tracks = tracksAdapter?.currentList.orEmpty()
                if (tracks.isEmpty()) return@TrackAdapter
                player.playTracks(tracks, position)
                (requireActivity() as MainActivity).showPlayer()
            },
            onLongClick = { _, _ -> /* adding to playlist now lives in Files tab */ },
            onMoreClick = { _, track -> showTrackActions(track) },
        ).also {
            it.setShowMore(true)
            it.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
            binding.libraryList.adapter = it
        }
    }

    private fun setUpHeader(binding: FragmentPlaylistBinding) {
        binding.btnBack.setOnClickListener { goBack(binding) }
        binding.btnSearch.setOnClickListener {
            binding.searchBar.visibility = View.VISIBLE
            binding.searchInput.requestFocus()
        }
        binding.btnSearchClose.setOnClickListener {
            binding.searchInput.setText("")
            binding.searchBar.visibility = View.GONE
            binding.searchInput.clearFocus()
        }
        binding.searchInput.doAfterTextChanged { text ->
            query.value = text?.toString()?.trim().orEmpty()
        }
        binding.btnSort.setOnClickListener { showSortDialog() }
        binding.btnFilter.setOnClickListener { showFilterDialog() }
        binding.btnNewPlaylist.setOnClickListener { promptNewPlaylist() }
    }

    private fun openEntry(entry: PlaylistsRootEntry) {
        val target = when (entry) {
            is PlaylistsRootEntry.All -> PlaylistTarget(null, getString(R.string.library_all_songs))
            is PlaylistsRootEntry.Playlist -> PlaylistTarget(
                entry.row.playlist.id,
                entry.row.playlist.name,
            )
        }
        openPlaylist.value = target
    }

    private fun goBack(binding: FragmentPlaylistBinding) {
        openPlaylist.value = null
    }

    // ---------------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------------

    private fun observePlaylists(binding: FragmentPlaylistBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    app.database.observePlaylistRows(),
                    app.database.trackDao().observeVisibleCount(),
                ) { rows, libraryCount -> rows to libraryCount }
                    .collect { (rows, libraryCount) ->
                        playlistNames = rows.associate { it.playlist.id to it.playlist.name }
                        totalTrackCount = libraryCount
                        // Rebuild the root list: virtual All + user playlists.
                        rootAdapter?.submitList(
                            buildList {
                                add(PlaylistsRootEntry.All(libraryCount))
                                addAll(rows.map { PlaylistsRootEntry.Playlist(it) })
                            },
                        )
                    }
            }
        }
    }

    /**
     * Switches between root view and a single-playlist's track list. The outer
     * collector repaints the chrome on every change; the inner collector is
     * cancelled before a new one starts, so switching playlists never feeds
     * stale tracks into the list.
     */
    private fun observeOpenPlaylist(binding: FragmentPlaylistBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var tracksCollector: kotlinx.coroutines.Job? = null
                openPlaylist.collect { target ->
                    val isRoot = target == null

                    binding.playlistsList.isVisible = isRoot
                    binding.libraryList.isVisible = !isRoot
                    binding.btnBack.isVisible = !isRoot
                    binding.btnNewPlaylist.isVisible = isRoot
                    binding.btnSearch.isVisible = !isRoot
                    binding.btnSort.isVisible = !isRoot
                    binding.btnFilter.isVisible = !isRoot
                    binding.libraryHeader.text = if (isRoot) {
                        getString(R.string.playlists_title)
                    } else {
                        target.name
                    }

                    // At root: clear search/filter, tear down the inner collector.
                    tracksCollector?.cancel()
                    tracksCollector = null
                    if (isRoot) {
                        query.value = ""
                        filter.value = TrackFilter.All
                        return@collect
                    }

                    val visibleFlow = if (target.id == null) {
                        app.database.trackDao().observeVisible()
                    } else {
                        app.database.playlistTrackDao().observeTracks(target.id)
                    }
                    tracksCollector = launch {
                        combine(visibleFlow, query, sort, filter) { tracks, text, order, kind ->
                            PlayRequest(tracks, text, order, kind)
                        }.collect { request -> renderInternal(binding, request) }
                    }
                }
            }
        }
    }

    private data class PlayRequest(
        val tracks: List<Track>,
        val search: String,
        val order: TrackSort,
        val kind: TrackFilter,
    )

    private fun renderInternal(binding: FragmentPlaylistBinding, request: PlayRequest) {
        currentTracks = request.tracks
        val visible = applyFilterAndSort(
            request.tracks, request.search, request.order, request.kind,
        )
        tracksAdapter?.submitList(visible)

        val narrowed = request.search.isNotEmpty() || request.kind != TrackFilter.All
        binding.libraryEmpty.isVisible = visible.isEmpty()
        if (visible.isEmpty()) {
            binding.libraryEmptyTitle.setText(
                if (narrowed) R.string.library_filter_empty else R.string.library_empty,
            )
            binding.libraryEmptyHint.setText(
                if (narrowed) R.string.library_filter_empty_hint else R.string.library_empty_hint,
            )
        }
    }

    private fun applyFilterAndSort(
        tracks: List<Track>,
        search: String,
        order: TrackSort,
        kind: TrackFilter,
    ): List<Track> {
        val scoped = if (kind == TrackFilter.All) tracks else tracks.filter { kind.matches(it) }
        val filtered = if (search.isEmpty()) {
            scoped
        } else {
            val needle = search.lowercase()
            scoped.filter { track ->
                track.title.lowercase().contains(needle) ||
                    track.artist?.lowercase()?.contains(needle) == true ||
                    track.album?.lowercase()?.contains(needle) == true
            }
        }
        return when (order) {
            TrackSort.DATE_ADDED_DESC -> filtered.sortedByDescending { it.dateAdded }
            TrackSort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            TrackSort.ARTIST_ASC -> filtered.sortedWith(
                compareBy({ it.artist?.lowercase() ?: "\uFFFF" }, { it.title.lowercase() }),
            )
            TrackSort.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
            TrackSort.SIZE_DESC -> filtered.sortedByDescending { it.fileSize }
        }
    }

    // ---------------------------------------------------------------------
    // Filter and sort
    // ---------------------------------------------------------------------

    private fun showFilterDialog() {
        val options = buildList {
            add(TrackFilter.All)
            add(TrackFilter.Audio)
            add(TrackFilter.Video)
            currentTracks.map { it.fileExtension }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(MAX_FILTER_FORMATS)
                .forEach { add(TrackFilter.Format(it.key)) }
        }
        val labels = options.map { it.label(requireContext()) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_filter)
            .setSingleChoiceItems(labels, options.indexOf(filter.value)) { dialog, which ->
                filter.value = options[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showSortDialog() {
        val labels = TrackSort.entries.map { sortLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_default_sort)
            .setSingleChoiceItems(labels, TrackSort.entries.indexOf(sort.value)) { dialog, which ->
                val chosen = TrackSort.entries[which]
                sort.value = chosen
                AudioCatApp.get().settings.update { it.copy(defaultSort = chosen.name) }
                dialog.dismiss()
            }
            .show()
    }

    private fun sortLabel(value: TrackSort): String = when (value) {
        TrackSort.DATE_ADDED_DESC -> "Recently added"
        TrackSort.TITLE_ASC -> "Title"
        TrackSort.ARTIST_ASC -> "Artist"
        TrackSort.DURATION_DESC -> "Longest first"
        TrackSort.SIZE_DESC -> "Largest first"
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private fun showTrackActions(track: Track) {
        val actions = arrayOf(
            getString(R.string.action_queue),
            if (track.unlisted) getString(R.string.files_relist) else getString(R.string.files_unlist),
            getString(R.string.files_info),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> player.playNext(listOf(track))
                    1 -> setUnlisted(track, !track.unlisted)
                    2 -> showFileInfo(track)
                }
            }
            .show()
    }

    private fun promptNewPlaylist() {
        val dialogBinding = DialogTextInputBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_new)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.playlist_create) { _, _ ->
                val name = dialogBinding.inputName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    app.database.playlistDao().insert(Playlist(name = name))
                }
            }
            .show()
    }

    private fun promptRename(playlist: Playlist) {
        val dialogBinding = DialogTextInputBinding.inflate(layoutInflater)
        dialogBinding.inputName.setText(playlist.name)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_rename)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.playlist_rename) { _, _ ->
                val name = dialogBinding.inputName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name == playlist.name) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    app.database.playlistDao().rename(playlist.id, name)
                }
            }
            .show()
    }

    private fun confirmDelete(playlist: Playlist) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_delete)
            .setMessage(getString(R.string.playlist_delete_confirm, playlist.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    app.database.playlistDao().delete(playlist.id)
                }
            }
            .show()
    }

    private fun setUnlisted(track: Track, unlisted: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.trackDao().setUnlisted(listOf(track.path), unlisted)
        }
    }

    private fun showFileInfo(track: Track) {
        val info = buildString {
            appendLine(getString(R.string.files_info))
            appendLine()
            appendLine(track.path)
            appendLine("${Formatters.fileSize(track.fileSize)} · ${Formatters.duration(track.durationMs)}")
            track.mimeType?.let { appendLine(it) }
            if (track.sampleRate > 0) appendLine("${track.sampleRate} Hz")
            if (track.bitRate > 0) appendLine("${track.bitRate / 1000} kbps")
            if (track.channelCount > 0) appendLine("${track.channelCount} channels")
        }
        AlertDialog.Builder(requireContext())
            .setMessage(info)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun observePlayback() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                player.currentTrack.collect { track ->
                    tracksAdapter?.setPlayingPath(track?.path)
                }
            }
        }
    }

    private fun snack(@StringRes message: Int) {
        binding?.let { Snackbar.make(it.root, message, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        binding?.playlistsList?.adapter = null
        binding?.libraryList?.adapter = null
        rootAdapter = null
        tracksAdapter = null
        binding = null
        super.onDestroyView()
    }

    /** Convenience for sheets that need the shared connection. */
    internal fun playerForSheets(): PlayerConnection = player

    /** Queue currently held by the service, used by the queue sheet. */
    internal fun currentQueue() = player.queue.value

    private companion object {
        const val MAX_FILTER_FORMATS = 8
    }
}

/** What is currently open on the playlist tab. */
data class PlaylistTarget(val id: Long?, val name: String)

/**
 * How the list is narrowed.
 */
sealed class TrackFilter {

    data object All : TrackFilter()
    data object Audio : TrackFilter()
    data object Video : TrackFilter()
    data class Format(val extension: String) : TrackFilter()

    fun matches(track: Track): Boolean = when (this) {
        All -> true
        Audio -> !track.isVideo
        Video -> track.isVideo
        is Format -> track.fileExtension == extension
    }

    fun label(context: Context): String = when (this) {
        All -> context.getString(R.string.library_all_songs)
        Audio -> context.getString(R.string.library_filter_audio)
        Video -> context.getString(R.string.library_filter_video)
        is Format -> extension.uppercase()
    }
}
