package com.audiocat.ui.effects

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.audiocat.databinding.ItemPresetChipBinding
import com.audiocat.effects.EffectPreset

/**
 * Renders the preset chooser as a row of filter chips.
 *
 * A tap applies a preset; a long press asks the host to remove it, which the
 * host refuses for built-ins. The chip matching the applied preset stays checked
 * so the screen always shows whether the current sound came from a preset.
 */
class PresetAdapter(
    private val onClick: (EffectPreset) -> Unit,
    private val onLongClick: (EffectPreset) -> Unit,
) : RecyclerView.Adapter<PresetAdapter.ChipHolder>() {

    private var items: List<EffectPreset> = emptyList()
    private var activeName: String? = null

    /** Replaces the shown presets; built-ins and custom presets come pre-combined. */
    fun submit(presets: List<EffectPreset>) {
        items = presets
        notifyDataSetChanged()
    }

    /** Marks [name] as the applied preset, or clears the mark when null. */
    fun setActive(name: String?) {
        if (activeName == name) return
        activeName = name
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipHolder =
        ChipHolder(ItemPresetChipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ChipHolder, position: Int) = holder.bind(items[position])

    inner class ChipHolder(private val binding: ItemPresetChipBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(preset: EffectPreset) {
            binding.presetChip.text = preset.name
            binding.presetChip.isChecked = preset.name == activeName
            binding.presetChip.setOnClickListener { onClick(preset) }
            binding.presetChip.setOnLongClickListener {
                onLongClick(preset)
                true
            }
        }
    }
}
