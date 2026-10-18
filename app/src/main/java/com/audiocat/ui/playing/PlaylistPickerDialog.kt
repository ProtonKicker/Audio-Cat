package com.audiocat.ui.playing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiocat.AudioCatApp
import com.audiocat.R
import com.audiocat.data.db.Playlist
import com.audiocat.databinding.DialogTextInputBinding
import com.audiocat.databinding.SheetPlaylistsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * "Add to playlist" picker.
 *
 * Takes the paths to add as an argument so any screen (library, files, search
 * results) can reuse it without knowing how playlists work.
 */
class PlaylistPickerDialog : BottomSheetDialogFragment() {

    private var binding: SheetPlaylistsBinding? = null

    private val paths: List<String> get() = requireArguments().getStringArrayList(ARG_PATHS).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SheetPlaylistsBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        // The header is replaced by the picker title; the list stays the same.
        binding.root.findViewById<android.widget.TextView>(R.id.playlistEmpty)
            .setText(R.string.playlist_empty)

        val adapter = PlaylistRowAdapter(showDelete = false, onClick = ::addToPlaylist)
        binding.playlistList.layoutManager = LinearLayoutManager(requireContext())
        binding.playlistList.adapter = adapter
        binding.btnNewPlaylist.setOnClickListener { promptNewPlaylist() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioCatApp.get().database.observePlaylistRows().collect { rows ->
                    binding.playlistEmpty.visibility =
                        if (rows.isEmpty()) View.VISIBLE else View.GONE
                    adapter.submitList(rows)
                }
            }
        }
    }

    private fun addToPlaylist(playlist: Playlist) {
        val paths = paths
        if (paths.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AudioCatApp.get().database.playlistTrackDao()
            var added = 0
            paths.forEach { path -> if (dao.append(playlist.id, path)) added++ }
            val message = if (added > 0) {
                getString(R.string.playlist_added, playlist.name)
            } else {
                getString(R.string.playlist_duplicate, playlist.name)
            }
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
            dismiss()
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
                    val id = AudioCatApp.get().database.playlistDao()
                        .insert(Playlist(name = name))
                    val dao = AudioCatApp.get().database.playlistTrackDao()
                    paths.forEach { path -> dao.append(id, path) }
                    Snackbar.make(
                        requireView(),
                        getString(R.string.playlist_added, name),
                        Snackbar.LENGTH_SHORT,
                    ).show()
                    dismiss()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        binding?.playlistList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "PlaylistPickerDialog"
        private const val ARG_PATHS = "paths"

        fun newInstance(paths: List<String>): PlaylistPickerDialog =
            PlaylistPickerDialog().apply {
                arguments = Bundle().apply { putStringArrayList(ARG_PATHS, ArrayList(paths)) }
            }
    }
}
