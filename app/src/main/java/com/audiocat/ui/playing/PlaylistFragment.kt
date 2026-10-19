package com.audiocat.ui.playing

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Playlist tab: a lane of playlists above the list they select.
 *
 * The lane is a fixed sibling of the list rather than a header inside it, so the
 * songs scroll underneath while the playlists stay visible - which is the point of
 * putting them there. The first card is always the whole library, so "show
 * everything" is one tap away from any playlist.
 *
 * Everything is Flow-driven: a scan, an unlist, a rename or a playlist edit
 * repaints both lists without a manual refresh call anywhere.
 */
class PlaylistFragment : Fragment(R.layout.fragment_playlist) {

    private var binding: FragmentPlaylistBinding? = null
    private var adapter: TrackAdapter? = null
    private var laneAdapter: PlaylistLaneAdapter? = null
    private var playlistsAdapter: PlaylistRowAdapter? = null

    /** The unfiltered rows of the current scope, used to offer format choices. */
    private var scopeTracks: List<Track> = emptyList()

    /** Playlist id per lane card, so the header can name the scope. */
    private var playlistNames: Map<Long, String> = emptyMap()

    private val app get() = AudioCatApp.get()

    private val player: PlayerConnection
        get() = (requireActivity() as MainActivity).player

    /** Live search text; empty means "show everything". */
    private val query = MutableStateFlow("")

    /** Sort order, seeded from settings when the view is created. */
    private val sort = MutableStateFlow(TrackSort.DATE_ADDED_DESC)

    /** Format narrowing, separate from sort so the two stay independent. */
    private val filter = MutableStateFlow<TrackFilter>(TrackFilter.All)

    /** Null while the lane selects the whole library. */
    private val selectedPlaylist = MutableStateFlow<Long?>(null)

    /** Toggle between songs view (default) and playlists-as-list view. */
    private val showPlaylistsView = MutableStateFlow(false)

    /** Batch selection state. */
    private var selectionMode = false
    private val selectedPaths = linkedSetOf<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentPlaylistBinding.bind(view).also { this.binding = it }

        sort.value = TrackSort.fromKey(AudioCatApp.get().settings.current.defaultSort)
        setUpList(binding)
        setUpLane(binding)
        setUpPlaylistsList(binding)
        setUpHeader(binding)
        setUpSelectionBar(binding)
        observePlaylists(binding)
        observeLibrary(binding)
        observePlayback()
        observeViewToggle(binding)
    }

    /** Reacts to the playlists-view toggle by swapping list visibility + header. */
    private fun observeViewToggle(binding: FragmentPlaylistBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                showPlaylistsView.collect { showLists ->
                    binding.libraryList.isVisible = !showLists
                    binding.playlistsList.isVisible = showLists
                    binding.btnSearch.isVisible = !showLists
                    binding.btnSort.isVisible = !showLists
                    binding.btnFilter.isVisible = !showLists
                    binding.libraryHeader.setText(
                        if (showLists) R.string.playlists_title else R.string.library_all_songs,
                    )
                    // The chevron icon points down when collapsed, up when expanded.
                    binding.btnExpandPlaylists.setImageResource(
                        if (showLists) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    )
                    // Hide the chevron itself while in expanded playlists view.
                    binding.laneRow.isVisible = laneRows.isNotEmpty() && !showLists
                }
            }
        }
    }

    private fun setUpList(binding: FragmentPlaylistBinding) {
        adapter = TrackAdapter(
            artwork = ArtworkLoader::uri,
            onClick = { position, track ->
                if (selectionMode) {
                    toggleSelection(track)
                } else {
                    val tracks = adapter?.currentList.orEmpty()
                    if (tracks.isEmpty()) return@TrackAdapter
                    player.playTracks(tracks, position)
                    (requireActivity() as MainActivity).showPlayer()
                }
            },
            onLongClick = { _, track ->
                if (!selectionMode && !showPlaylistsView.value) {
                    selectionMode = true
                    selectedPaths += track.path
                    renderSelection()
                }
            },
            onMoreClick = { _, track -> showTrackActions(track) },
        ).also {
            it.setShowMore(true)
            // Without this, a restored ViewPager2 page can end up showing the
            // stale, empty instance of the fragment while the live one collects
            // in the background: the list renders zero rows forever.
            it.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
            binding.libraryList.adapter = it
        }
    }

    private fun setUpPlaylistsList(binding: FragmentPlaylistBinding) {
        playlistsAdapter = PlaylistRowAdapter(
            showDelete = true,
            onClick = { playlist ->
                if (showPlaylistsView.value) {
                    PlaylistDetailSheet.newInstance(playlist.id, playlist.name)
                        .show(parentFragmentManager, PlaylistDetailSheet.TAG)
                }
            },
            onDelete = { playlist -> confirmDelete(playlist) },
        ).also {
            binding.playlistsList.layoutManager = LinearLayoutManager(requireContext())
            binding.playlistsList.adapter = it
        }
    }

    private fun setUpLane(binding: FragmentPlaylistBinding) {
        laneAdapter = PlaylistLaneAdapter(
            onClick = { item -> selectedPlaylist.value = item.playlistId },
            onLongClick = { item -> showPlaylistActions(item) },
            onMoved = { from, to -> onLaneItemMoved(from, to) },
        ).also { adapter ->
            binding.playlistLane.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            binding.playlistLane.adapter = adapter
            // Drag-to-reorder: horizontal lane means only left/right moves.
            // The callback is created first so both the attach + the drag-start
            // hook can share a single instance.
            val callback = object : ItemTouchHelper.Callback() {
                override fun isLongPressDragEnabled() = false

                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    // The "All songs" card cannot be dragged.
                    if (viewHolder.bindingAdapterPosition == 0) {
                        return makeMovementFlags(0, 0)
                    }
                    return makeMovementFlags(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                    adapter.moveLaneItem(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    // Drag finished — hand the new order to the owner so it sticks.
                    onLaneOrderCommitted(adapter.committedOrder())
                }
            }
            val touchHelper = ItemTouchHelper(callback)
            adapter.onStartDrag = { vh -> touchHelper.startDrag(vh) }
            touchHelper.attachToRecyclerView(binding.playlistLane)
        }
    }

    /** Persists a finished drag as the new playlist-order for this session. */
    private fun onLaneItemMoved(from: Int, to: Int) {
        // Local-only: the UI already tracks the finger via moveLaneItem; no DB
        // write per frame. The authoritative write happens in onLaneOrderCommitted.
    }

    /** Rewrites the stored lane order after a drag ends. */
    private fun onLaneOrderCommitted(order: List<LaneItem>) {
        val ids = order.mapNotNull { it.playlistId }
        viewLifecycleOwner.lifecycleScope.launch {
            ids.forEachIndexed { index, id ->
                app.database.playlistDao().setLaneOrder(id, index)
            }
        }
    }

    private fun setUpHeader(binding: FragmentPlaylistBinding) {
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
        binding.btnExpandPlaylists.setOnClickListener {
            showPlaylistsView.value = !showPlaylistsView.value
        }
    }

    private fun setUpSelectionBar(binding: FragmentPlaylistBinding) {
        binding.btnSelectionClose.setOnClickListener { exitSelection() }
        binding.btnSelectAll.setOnClickListener {
            val allPaths = adapter?.currentList?.map { it.path } ?: emptyList()
            selectedPaths.clear()
            selectedPaths.addAll(allPaths)
            renderSelection()
        }
        binding.btnAddToPlaylist.setOnClickListener {
            if (selectedPaths.isNotEmpty()) {
                PlaylistPickerDialog.newInstance(selectedPaths.toList())
                    .show(parentFragmentManager, PlaylistPickerDialog.TAG)
                exitSelection()
            }
        }
    }

    private fun toggleSelection(track: Track) {
        if (!selectedPaths.remove(track.path)) selectedPaths += track.path
        if (selectedPaths.isEmpty()) exitSelection() else renderSelection()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPaths.clear()
        renderSelection()
    }

    private fun renderSelection() {
        val binding = binding ?: return
        binding.selectionBar.isVisible = selectionMode
        binding.selectionCount.text = getString(R.string.files_selected, selectedPaths.size)
        adapter?.setSelectionMode(selectionMode)
        adapter?.setSelectedPaths(selectedPaths.toSet())
        adapter?.setShowMore(!selectionMode)
    }

    // Snapshot of the current playlist rows, kept so the lane and list agree
    // on what exists without re-querying the Flow on every header click.
    private var laneRows: List<PlaylistRow> = emptyList()

    // ---------------------------------------------------------------------
    // The lane
    // ---------------------------------------------------------------------

    private fun observePlaylists(binding: FragmentPlaylistBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    app.database.observePlaylistRows(),
                    app.database.trackDao().observeVisibleCount(),
                ) { rows, libraryCount -> rows to libraryCount }
                    .collect { (rows, libraryCount) ->
                        laneRows = rows
                        playlistNames = rows.associate { it.playlist.id to it.playlist.name }
                        laneAdapter?.submitList(
                            buildList {
                                add(LaneItem.All(libraryCount))
                                addAll(rows.map { LaneItem.Playlist(it, it.playlist.id) })
                            },
                        )
                        // Feed the vertical playlists list.
                        playlistsAdapter?.submitList(rows)
                        // The toggle observer fires once at startup before laneRows is
                        // populated, so refresh laneRow visibility here too.
                        binding.laneRow.isVisible = rows.isNotEmpty() && !showPlaylistsView.value
                    }
            }
        }
    }

    /** Long-press menu for a lane card: the only place a playlist is renamed. */
    private fun showPlaylistActions(item: LaneItem) {
        val row = (item as? LaneItem.Playlist)?.row ?: return
        val playlist = row.playlist
        val actions = arrayOf(
            getString(R.string.playlist_play_all),
            getString(R.string.playlist_edit_tracks),
            getString(R.string.playlist_rename),
            getString(R.string.playlist_delete),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(playlist.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> playPlaylist(playlist.id)
                    1 -> PlaylistDetailSheet.newInstance(playlist.id, playlist.name)
                        .show(parentFragmentManager, PlaylistDetailSheet.TAG)
                    2 -> promptRename(playlist)
                    else -> confirmDelete(playlist)
                }
            }
            .show()
    }

    private fun playPlaylist(playlistId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = app.database.playlistTrackDao().observeTracks(playlistId).firstOrNull().orEmpty()
            if (tracks.isEmpty()) {
                snack(R.string.playlist_empty_cannot_play)
                return@launch
            }
            player.playTracks(tracks, 0)
            (requireActivity() as MainActivity).showPlayer()
        }
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
                    // Only the playlist row goes away; the tracks stay on disk
                    // and remain in the library.
                    app.database.playlistDao().delete(playlist.id)
                }
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // The list below it
    // ---------------------------------------------------------------------

    /**
     * Renders the list under the lane.
     *
     * The selection chooses the *source* - the library, or one playlist - and the
     * inner collector is cancelled before the next one starts, so switching scopes
     * can never leave two sources feeding one list.
     */
    private fun observeLibrary(binding: FragmentPlaylistBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var inner: Job? = null
                selectedPlaylist.collect { playlistId ->
                    laneAdapter?.setSelectedId(playlistId)
                    inner?.cancel()
                    val source = if (playlistId == null) {
                        app.database.trackDao().observeVisible()
                    } else {
                        app.database.playlistTrackDao().observeTracks(playlistId)
                    }
                    inner = launch {
                        combine(source, query, sort, filter) { tracks, text, order, kind ->
                            ViewRequest(tracks, text, order, kind)
                        }.collect { request -> render(binding, playlistId, request) }
                    }
                }
            }
        }
    }

    private data class ViewRequest(
        val tracks: List<Track>,
        val search: String,
        val order: TrackSort,
        val kind: TrackFilter,
    )

    private fun render(binding: FragmentPlaylistBinding, playlistId: Long?, request: ViewRequest) {
        scopeTracks = request.tracks
        val visible = applyFilterAndSort(request.tracks, request.search, request.order, request.kind)
        adapter?.submitList(visible)

        val narrowed = request.search.isNotEmpty() || request.kind != TrackFilter.All
        binding.libraryEmpty.isVisible = visible.isEmpty() && !showPlaylistsView.value
        if (visible.isEmpty()) {
            binding.libraryEmptyTitle.setText(
                if (narrowed) R.string.library_filter_empty else R.string.library_empty,
            )
            binding.libraryEmptyHint.setText(
                if (narrowed) R.string.library_filter_empty_hint else R.string.library_empty_hint,
            )
        }

        // Only update the header while in songs mode; the view-toggle observer
        // owns the header text in playlists mode.
        if (!showPlaylistsView.value) {
            binding.libraryHeader.text = when {
                narrowed -> resources.getQuantityString(R.plurals.library_count, visible.size, visible.size)
                playlistId == null -> getString(R.string.library_all_songs)
                else -> playlistNames[playlistId].orEmpty()
            }
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

    /**
     * One flat list, because "Audio only" and "MP3" are the same kind of question
     * asked at different precision. The container choices come from what the
     * library actually holds, so no entry can be tapped into an empty list.
     */
    private fun showFilterDialog() {
        val options = buildList {
            add(TrackFilter.All)
            add(TrackFilter.Audio)
            add(TrackFilter.Video)
            scopeTracks.map { it.fileExtension }
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
    // Row actions
    // ---------------------------------------------------------------------

    /** Long-press and overflow actions for a single library row. */
    private fun showTrackActions(track: Track) {
        val actions = arrayOf(
            getString(R.string.files_add_playlist),
            getString(R.string.action_queue),
            if (track.unlisted) getString(R.string.files_relist) else getString(R.string.files_unlist),
            getString(R.string.files_info),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> PlaylistPickerDialog.newInstance(listOf(track.path))
                        .show(parentFragmentManager, PlaylistPickerDialog.TAG)
                    1 -> player.playNext(listOf(track))
                    2 -> setUnlisted(track, !track.unlisted)
                    3 -> showFileInfo(track)
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
                    adapter?.setPlayingPath(track?.path)
                }
            }
        }
    }

    private fun snack(@StringRes message: Int) {
        binding?.let { Snackbar.make(it.root, message, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        binding?.libraryList?.adapter = null
        binding?.playlistLane?.adapter = null
        binding?.playlistsList?.adapter = null
        adapter = null
        laneAdapter = null
        playlistsAdapter = null
        binding = null
        super.onDestroyView()
    }

    /** Convenience for sheets that need the shared connection. */
    internal fun playerForSheets(): PlayerConnection = player

    /** Queue currently held by the service, used by the queue sheet. */
    internal fun currentQueue() = player.queue.value

    private companion object {
        /** Containers offered before the list stops being worth scrolling. */
        const val MAX_FILTER_FORMATS = 8
    }
}

/**
 * How the list under the lane is narrowed.
 *
 * Containers come from the file extension rather than MediaStore's mime type: on
 * the test phone an .m4a is reported as `audio/mp4a-latm` while .mp3 rows are
 * `audio/mpeg`, so a mime-based filter splits one format across two keys.
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
