package com.audiocat.ui.effects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.lifecycleScope
import com.audiocat.AudioCatApp
import com.audiocat.R
import com.audiocat.data.SettingsStore
import com.audiocat.databinding.DialogTextInputBinding
import com.audiocat.databinding.FragmentEffectsBinding
import com.audiocat.effects.EffectPreset
import com.audiocat.effects.EffectsEngine
import com.audiocat.effects.EqBand
import com.audiocat.effects.ReverbPreset
import com.audiocat.playback.PlaybackService
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Live control surface for the audio effects: equaliser, bass boost, reverb and
 * speed/pitch.
 *
 * Nothing here is buffered behind an "apply" button - every gesture is pushed to
 * the running engine immediately, which is what makes the screen useful for
 * tuning while listening. Because the platform effect owns the authoritative
 * values, the controls are re-read from the engine on resume rather than from
 * whatever the user last touched.
 */
@AndroidXOptIn(UnstableApi::class)
class EffectsFragment : Fragment(R.layout.fragment_effects) {

    private var _binding: FragmentEffectsBinding? = null
    private val binding get() = _binding!!

    private val settings: SettingsStore get() = AudioCatApp.get().settings

    private lateinit var presets: EffectPresetsStore
    private lateinit var presetAdapter: PresetAdapter
    private var bandAdapter: EqBandAdapter? = null

    private val reverbChips = mutableListOf<Chip>()
    private var engineWatch: Job? = null
    private var suppressSwitchCallback = false

    /** The running engine, or null while the playback service does not exist. */
    private val engine: EffectsEngine? get() = PlaybackService.peek()?.effects

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEffectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        presets = EffectPresetsStore(requireContext())
        presetAdapter = PresetAdapter(onClick = ::applyPreset, onLongClick = ::confirmDeletePreset)
        binding.presetList.adapter = presetAdapter

        buildReverbChips()
        binding.bassSeek.onUserProgress { progress ->
            engine?.takeIf { it.isAvailable }?.setBassBoost(progress / SEEK_MAX)
            markCustomised()
        }
        binding.speedSeek.onUserProgress {
            applySpeedPitch(speedFromProgress(it), settings.current.playbackPitch)
        }
        binding.pitchSeek.onUserProgress {
            applySpeedPitch(settings.current.playbackSpeed, pitchFromProgress(it))
        }
        binding.btnEqReset.setOnClickListener { resetBands() }
        binding.btnSpeedReset.setOnClickListener { applySpeedPitch(DEFAULT_SPEED, DEFAULT_PITCH) }
        binding.btnSavePreset.setOnClickListener { promptSavePreset() }
        binding.switchEffects.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            settings.update { it.copy(effectsEnabled = checked) }
            engine?.setEnabled(checked)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        watchForEngine()
    }

    override fun onPause() {
        engineWatch?.cancel()
        super.onPause()
    }

    override fun onDestroyView() {
        bandAdapter = null
        reverbChips.clear()
        binding.presetList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    /** Re-reads every value from the engine and from the persisted settings. */
    private fun refresh() {
        val effects = engine
        val available = effects != null && effects.isAvailable

        binding.effectsUnavailable.visibility = if (available) View.GONE else View.VISIBLE
        setEffectControlsEnabled(available)
        setSwitchSilently(settings.current.effectsEnabled)
        renderEffectSection(effects)

        val current = settings.current
        renderSpeedPitch(current.playbackSpeed, current.playbackPitch)
        presetAdapter.submit(presets.all())
        presetAdapter.setActive(current.activePreset)
    }

    /** Renders the equaliser bank, bass and reverb, or empties them when unsupported. */
    private fun renderEffectSection(effects: EffectsEngine?) {
        if (effects == null || !effects.isAvailable) {
            binding.eqSummary.text = ""
            binding.eqBands.removeAllViews()
            bandAdapter = null
            return
        }
        val bands = effects.availableBands
        binding.eqSummary.text = eqSummaryOf(bands, effects.usesCascade, effects.maxFrequencyHz)
        bindBands(bands)
        renderEngineValues()
    }

    /**
     * The engine appears only once the service holds an audio session, which can
     * be long after this tab was first opened, so watch for it rather than leaving
     * an unavailable screen behind.
     */
    private fun watchForEngine() {
        engineWatch?.cancel()
        if (engine?.isAvailable == true) return
        engineWatch = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && engine?.isAvailable != true) delay(ENGINE_POLL_MS)
            if (isActive) refresh()
        }
    }

    /** Reuses the slider views unless the device's band layout actually changed. */
    private fun bindBands(bands: List<EqBand>) {
        val adapter = bandAdapter
        if (adapter != null && adapter.matches(bands)) {
            adapter.updateGains(bands.map { it.gainDb })
        } else {
            bandAdapter = EqBandAdapter(binding.eqBands, ::onBandGainChanged).also { it.bind(bands) }
        }
    }

    /**
     * Mirrors what the engine actually holds onto the EQ, bass and reverb
     * controls: a value the platform rejected must not be shown as applied.
     */
    private fun renderEngineValues() {
        val effects = engine ?: return
        // capturePreset doubles as the engine's read-back path for live values.
        val snapshot = effects.capturePreset(SNAPSHOT_NAME)
        bandAdapter?.updateGains(snapshot.bandGainsDb)
        binding.bassSeek.progress = (snapshot.bassBoost * SEEK_MAX).roundToInt()
            .coerceIn(0, binding.bassSeek.max)
        selectReverbChip(snapshot.reverb)
    }

    /**
     * Speed and pitch stay usable without the effects engine: they belong to the
     * player, and the persisted values are picked up when the service starts.
     */
    private fun setEffectControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        listOf(
            binding.switchEffects,
            binding.presetList,
            binding.btnSavePreset,
            binding.eqScroll,
            binding.btnEqReset,
            binding.bassSeek,
            binding.reverbChips,
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        reverbChips.forEach { it.isEnabled = enabled }
        bandAdapter?.setEnabled(enabled)
    }

    // ---------------------------------------------------------------------
    // Equaliser and presets
    // ---------------------------------------------------------------------

    private fun onBandGainChanged(bandIndex: Int, gainDb: Float) {
        engine?.setBandGain(bandIndex, gainDb)
        markCustomised()
    }

    private fun resetBands() {
        val effects = engine ?: return
        if (!effects.isAvailable) return
        effects.setAllBandGains(List(effects.availableBands.size) { 0f })
        renderEngineValues()
        markCustomised()
    }

    /** Applies [preset], leaving speed and pitch to the playback settings. */
    private fun applyPreset(preset: EffectPreset) {
        val effects = engine ?: return
        if (!effects.isAvailable) return
        effects.applyPreset(preset)
        settings.update { it.copy(activePreset = preset.name) }
        presetAdapter.setActive(preset.name)
        renderEngineValues()
    }

    /** The sound no longer matches the applied preset, so drop the highlight. */
    private fun markCustomised() {
        if (settings.current.activePreset == null) return
        settings.update { it.copy(activePreset = null) }
        presetAdapter.setActive(null)
    }

    private fun promptSavePreset() {
        val effects = engine ?: return
        if (!effects.isAvailable) return

        val dialogBinding = DialogTextInputBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.effects_save_preset)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok, null)
            .create()

        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.isEnabled = false
            dialogBinding.inputName.addTextChangedListener {
                confirm.isEnabled = !it.isNullOrBlank()
            }
            confirm.setOnClickListener {
                val name = dialogBinding.inputName.text?.toString()?.trim().orEmpty()
                val preset = effects.capturePreset(name)
                presets.save(preset)
                presetAdapter.submit(presets.all())
                applyPreset(preset)
                Toast.makeText(requireContext(), R.string.effects_preset_saved, Toast.LENGTH_SHORT)
                    .show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeletePreset(preset: EffectPreset) {
        if (preset.builtIn) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.effects_delete_preset)
            .setMessage(preset.name)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (!presets.delete(preset.name)) return@setPositiveButton
                if (settings.current.activePreset == preset.name) {
                    settings.update { it.copy(activePreset = null) }
                }
                presetAdapter.submit(presets.all())
                presetAdapter.setActive(settings.current.activePreset)
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // Reverb
    // ---------------------------------------------------------------------

    private fun buildReverbChips() {
        val group = binding.reverbChips
        group.removeAllViews()
        reverbChips.clear()
        ReverbPreset.entries.forEach { preset ->
            val chip = Chip(requireContext()).apply {
                // ChipGroup's single selection tracks children by id.
                id = View.generateViewId()
                text = reverbLabel(preset)
                isCheckable = true
                tag = preset
                setOnClickListener { onReverbSelected(preset) }
            }
            group.addView(chip)
            reverbChips += chip
        }
    }

    private fun onReverbSelected(preset: ReverbPreset) {
        val effects = engine ?: return
        if (!effects.isAvailable) return
        effects.setReverb(preset)
        markCustomised()
        // Re-assert the choice: a tap on the checked chip unchecks it.
        selectReverbChip(preset)
    }

    private fun selectReverbChip(preset: ReverbPreset) {
        reverbChips.forEach { it.isChecked = it.tag == preset }
    }

    /** "SMALL_ROOM" reads as "Small room"; the layout has no per-preset strings. */
    private fun reverbLabel(preset: ReverbPreset): String = preset.name
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    // ---------------------------------------------------------------------
    // Speed and pitch
    // ---------------------------------------------------------------------

    /** Persists speed/pitch, pushes them to the live player and redraws the sliders. */
    private fun applySpeedPitch(speed: Float, pitch: Float) {
        settings.update { it.copy(playbackSpeed = speed, playbackPitch = pitch) }
        PlaybackService.peek()?.setSpeedAndPitch(speed, pitch)
        renderSpeedPitch(speed, pitch)
    }

    private fun renderSpeedPitch(speed: Float, pitch: Float) {
        binding.speedSeek.progress = speedToProgress(speed)
        binding.pitchSeek.progress = pitchToProgress(pitch)
        binding.speedValue.text = formatMultiplier(speed)
        binding.pitchValue.text = formatMultiplier(pitch)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun setSwitchSilently(checked: Boolean) {
        suppressSwitchCallback = true
        binding.switchEffects.isChecked = checked
        suppressSwitchCallback = false
    }

    private fun eqSummaryOf(bands: List<EqBand>, cascaded: Boolean, maxHz: Int): String {
        val high = bands.lastOrNull()?.maxHz?.takeIf { it > 0 } ?: maxHz
        return buildString {
            append(bands.size).append(" bands")
            if (cascaded) append(" · dual stage")
            if (high > 0) append(" · up to ").append(formatFrequency(high))
        }
    }

    /** Reports user-driven changes only, so programmatic updates stay silent. */
    private fun SeekBar.onUserProgress(action: (Int) -> Unit) {
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) action(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )
    }

    private companion object {
        /** capturePreset only uses the name as a label; these snapshots are transient. */
        const val SNAPSHOT_NAME = "current"
        const val DISABLED_ALPHA = 0.4f
        const val ENGINE_POLL_MS = 1_000L

        const val SEEK_MAX = 100f
        const val DEFAULT_SPEED = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f

        /** Slider 0..100 maps onto the range logarithmically, centring 1.00x. */
        fun speedFromProgress(progress: Int): Float =
            MIN_SPEED * (MAX_SPEED / MIN_SPEED).pow(progress / SEEK_MAX)

        fun speedToProgress(speed: Float): Int =
            (SEEK_MAX * ln(speed / MIN_SPEED) / ln(MAX_SPEED / MIN_SPEED))
                .roundToInt().coerceIn(0, SEEK_MAX.toInt())

        fun pitchFromProgress(progress: Int): Float =
            MIN_PITCH * (MAX_PITCH / MIN_PITCH).pow(progress / SEEK_MAX)

        fun pitchToProgress(pitch: Float): Int =
            (SEEK_MAX * ln(pitch / MIN_PITCH) / ln(MAX_PITCH / MIN_PITCH))
                .roundToInt().coerceIn(0, SEEK_MAX.toInt())

        fun formatMultiplier(value: Float): String = String.format(Locale.US, "%.2fx", value)
    }
}
