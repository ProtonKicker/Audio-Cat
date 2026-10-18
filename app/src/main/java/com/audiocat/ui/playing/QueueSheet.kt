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
import com.audiocat.MainActivity
import com.audiocat.R
import com.audiocat.playback.PlayerConnection
import com.audiocat.databinding.SheetQueueBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * Queue sheet: shows the play order, supports drag-to-reorder and removal.
 *
 * Reordering is committed through the service so the player's item order and the
 * visible list can never drift apart.
 */
class QueueSheet : BottomSheetDialogFragment() {

    private var binding: SheetQueueBinding? = null
    private var adapter: QueueAdapter? = null

    private val player: PlayerConnection
        get() = (requireActivity() as MainActivity).player

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SheetQueueBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        val adapter = QueueAdapter(
            onClick = { position -> player.jumpToQueuePosition(position) },
            onRemove = { position -> player.removeFromQueue(position) },
        )
        this.adapter = adapter
        binding.queueList.layoutManager = LinearLayoutManager(requireContext())
        binding.queueList.adapter = adapter
        attachDragToReorder(binding.queueList)

        binding.btnClearQueue.setOnClickListener {
            player.clearQueue()
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                player.queue.collect { queue ->
                    val binding = binding ?: return@collect
                    val items = queue.orderedTracks
                    adapter.submit(items, queue.cursor)
                    binding.queueEmpty.visibility =
                        if (items.isEmpty()) View.VISIBLE else View.GONE
                    binding.btnClearQueue.isEnabled = items.isNotEmpty()
                }
            }
        }
    }

    /**
     * Enables drag-to-reorder.
     *
     * Dragging is started from the row's handle rather than by long-pressing the
     * whole row, so vertical scrolling keeps working normally.
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
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // Update the visible list immediately; the service confirms.
                adapter?.moveLocally(from, to)
                player.moveInQueue(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            // Dragging is initiated by the handle, never by a row long-press.
            override fun isLongPressDragEnabled(): Boolean = false
        }
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(list)
        adapter?.onStartDrag = { holder -> helper.startDrag(holder) }
    }

    override fun onDestroyView() {
        binding?.queueList?.adapter = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "QueueSheet"

        fun newInstance(): QueueSheet = QueueSheet()
    }
}
