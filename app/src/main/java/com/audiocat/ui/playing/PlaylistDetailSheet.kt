package com.audiocat.ui.playing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.AudioCatApp
import com.audiocat.MainActivity
import com.audiocat.R
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.data.db.Track
import com.audiocat.databinding.SheetPlaylistDetailBinding
import com.audiocat.playback.PlayerConnection
import com.audiocat.ui.common.ArtworkLoader
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Playlist detail: play the playlist, reorder and remove its tracks.
 *
 * Ordering is stored as a contiguous `position` column, so reordering rewrites
 * positions in one transaction rather than relying on insertion order.
 */
class PlaylistDetailSheet : BottomSheetDialogFragment() {

    private var binding: SheetPlaylistDetailBinding? = null
    private var adapter: TrackAdapter? = null

    private val playlistId: Long get() = requireArguments().getLong(ARG_ID)
    private val playlistName: String get() = requireArguments().getString(ARG_NAME).orEmpty()

    private val database: AudioCatDatabase get() = AudioCatApp.get().database
    private val player: PlayerConnection get() = (requireActivity() as MainActivity).player

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SheetPlaylistDetailBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        binding.detailTitle.text = playlistName

        val rows = TrackAdapter(
            artwork = ArtworkLoader::uri,
            // Resolved through the field so the click always sees the current
            // ordering, even after a drag reorder.
            onClick = { position, _ ->
                val current = adapter?.currentList.orEmpty()
                if (current.isEmpty()) return@TrackAdapter
                player.playTracks(current, position)
                (requireActivity() as MainActivity).showPlayer()
            },
            // Long press belongs to the drag handle here - ItemTouchHelper starts a
            // drag on the same gesture, so the row must not also open a dialog.
            // Removing a track is the dots' job.
            onMoreClick = { _, track -> confirmRemove(track) },
        )
        adapter = rows
        binding.detailList.layoutManager = LinearLayoutManager(requireContext())
        binding.detailList.adapter = rows
        rows.setShowMore(true)
        attachDragToReorder(binding.detailList)

        binding.btnPlayAll.setOnClickListener {
            val tracks = rows.currentList
            if (tracks.isEmpty()) return@setOnClickListener
            player.playTracks(tracks, 0)
            (requireActivity() as MainActivity).showPlayer()
        }

        // "Add tracks" opens the library-level picker in reverse: the user picks
        // from the whole library.
        binding.btnAddTracks.setOnClickListener { showLibraryPicker() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                database.playlistTrackDao().observeTracks(playlistId).collect { tracks ->
                    binding.detailEmpty.visibility =
                        if (tracks.isEmpty()) View.VISIBLE else View.GONE
                    rows.submitList(tracks)
                }
            }
        }
    }

    /**
     * Drag-to-reorder driven by the row's own long-press.
     *
     * The library rows do not have a drag handle, so long-press drag is enabled
     * here and reordering is committed when the drag finishes.
     */
    private fun attachDragToReorder(list: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val adapter = adapter ?: return false
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val items = adapter.currentList.toMutableList()
                items.add(to, items.removeAt(from))
                adapter.submitList(items)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Persist once, after the gesture, instead of on every move.
                val ordered = adapter?.currentList?.map { it.path } ?: return
                viewLifecycleOwner.lifecycleScope.launch {
                    database.playlistTrackDao().reorder(playlistId, ordered)
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(list)
    }

    private fun confirmRemove(track: Track) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title)
            .setItems(arrayOf("Remove from playlist")) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    // Removes the membership only; the file stays on disk.
                    database.playlistTrackDao().removeAndCompact(playlistId, track.path)
                }
            }
            .show()
    }

    /** Lets the user add library tracks to this playlist. */
    private fun showLibraryPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = database.trackDao().visibleOnce()
            val labels = tracks.map { it.title }.toTypedArray()
            val checked = BooleanArray(tracks.size) { index ->
                database.playlistTrackDao().contains(playlistId, tracks[index].path) > 0
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playlist_added)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        tracks.forEachIndexed { index, track ->
                            if (checked[index]) {
                                database.playlistTrackDao().append(playlistId, track.path)
                            } else {
                                database.playlistTrackDao().remove(playlistId, track.path)
                            }
                        }
                        database.playlistTrackDao().compact(playlistId)
                    }
                }
                .show()
        }
    }

    override fun onDestroyView() {
        binding?.detailList?.adapter = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "PlaylistDetailSheet"
        private const val ARG_ID = "playlistId"
        private const val ARG_NAME = "playlistName"

        fun newInstance(playlistId: Long, name: String): PlaylistDetailSheet =
            PlaylistDetailSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, playlistId)
                    putString(ARG_NAME, name)
                }
            }
    }
}
