package com.audiocat.ui.playing

import android.os.Bundle
import android.view.View
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.audiocat.AudioCatApp
import com.audiocat.MainActivity
import com.audiocat.R
import com.audiocat.data.RepeatMode
import com.audiocat.databinding.FragmentNowPlayingBinding
import com.audiocat.playback.PlayerConnection
import com.audiocat.ui.common.ArtworkLoader
import com.audiocat.ui.common.Formatters
import com.audiocat.ui.common.SpeedPicker
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Player tab: the full-page transport surface.
 *
 * This is the old Now Playing sheet promoted to a first-class page — artwork,
 * seek, transport, speed and queue all live here. The logic mirrors
 * [NowPlayingSheet] deliberately: the sheet stays for callers that still want
 * an overlay, while this fragment owns the tab.
 */
@AndroidXOptIn(UnstableApi::class)
class PlayerFragment : Fragment(R.layout.fragment_now_playing) {

    private var binding: FragmentNowPlayingBinding? = null

    private val player: PlayerConnection
        get() = (requireActivity() as MainActivity).player

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentNowPlayingBinding.bind(view).also { this.binding = it }

        // The collapse affordance only makes sense in the sheet.
        binding.btnCollapse.visibility = View.GONE

        binding.bigPlayPause.setOnClickListener { player.playPause() }
        binding.bigNext.setOnClickListener { player.next() }
        binding.bigPrevious.setOnClickListener { player.previous() }
        binding.bigShuffle.setOnClickListener { player.setShuffle(!player.shuffle.value) }
        binding.bigRepeat.setOnClickListener { player.cycleRepeat() }
        binding.btnSpeed.setOnClickListener { SpeedPicker.show(requireContext()) }
        binding.btnQueueSheet.setOnClickListener {
            QueueSheet.newInstance().show(parentFragmentManager, QueueSheet.TAG)
        }
        // Tapping the artwork opens the cover sheet for whatever is playing: that
        // is where a user looks when they want to change the picture.
        binding.bigArt.setOnClickListener {
            val path = player.currentTrack.value?.path ?: return@setOnClickListener
            CoverSheet.newInstance(path).show(parentFragmentManager, CoverSheet.TAG)
        }

        binding.bigSeek.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = player.durationMs.value
                    if (duration > 0) {
                        binding.bigPosition.text = Formatters.duration(duration * value / 1000)
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                    player.seekToFraction(seekBar.progress / 1000f)
                }
            },
        )
        observe(binding)
    }

    private fun observe(binding: FragmentNowPlayingBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(player.currentTrack, player.isPlaying) { track, playing -> track to playing }
                        .collect { (track, playing) ->
                            binding.bigTitle.text = track?.title ?: getString(R.string.now_playing_nothing)
                            binding.bigArtist.text = track?.artist
                                ?: getString(R.string.now_playing_unknown_artist)
                            binding.bigPlayPause.setImageResource(
                                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                            )
                            ArtworkLoader.load(binding.bigArt, track?.artPath)
                        }
                }
                launch {
                    combine(player.positionMs, player.durationMs, ::Pair)
                        .collect { (position, duration) ->
                            binding.bigPosition.text = Formatters.duration(position)
                            binding.bigDuration.text = Formatters.duration(duration)
                            if (duration > 0 && !binding.bigSeek.isPressed) {
                                binding.bigSeek.progress = (position * 1000 / duration).toInt()
                                    .coerceIn(0, 1000)
                            }
                        }
                }
                launch {
                    player.shuffle.collect { binding.bigShuffle.setColorFilter(accentOrMuted(it)) }
                }
                launch {
                    player.repeat.collect { mode ->
                        binding.bigRepeat.setImageResource(
                            if (mode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                        )
                        binding.bigRepeat.setColorFilter(accentOrMuted(mode != RepeatMode.OFF))
                    }
                }
                launch {
                    AudioCatApp.get().settings.settings
                        .collect { binding.btnSpeed.text = SpeedPicker.format(it.playbackSpeed) }
                }
            }
        }
    }

    private fun accentOrMuted(active: Boolean): Int = MaterialColors.getColor(
        requireView(),
        if (active) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        },
    )

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
