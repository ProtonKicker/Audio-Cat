package com.audiocat.ui.files

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.audiocat.AudioCatApp
import com.audiocat.R
import com.audiocat.databinding.DialogBatchRenameBinding
import com.audiocat.files.RenameOutcome
import com.audiocat.files.RenamePattern
import com.audiocat.files.RenamePlanItem
import com.audiocat.files.RenamePlanner
import com.audiocat.files.RenameTarget
import com.audiocat.files.WriteConsentRequired
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Applies a rename plan and survives Android's write-consent prompt.
 *
 * Scoped storage can refuse a rename mid-flight ([WriteConsentRequired]); the
 * prompt-and-retry cycle is needed by both this file's batch dialog and the
 * single-file rename in [FilesFragment], so it exists exactly once here.
 *
 * @param scope should be the caller's view lifecycle scope so a pending retry
 *   is dropped when the screen goes away.
 */
internal class RenameRunner(
    fragment: Fragment,
    private val scope: CoroutineScope,
    private val onFinished: (RenameOutcome) -> Unit,
) {
    private val fileOperations = AudioCatApp.get().fileOperations

    /** Plan waiting for the consent result; null when nothing is pending. */
    private var pending: List<RenamePlanItem>? = null

    private val consentLauncher: ActivityResultLauncher<IntentSenderRequest> =
        fragment.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            val plan = pending ?: return@registerForActivityResult
            pending = null
            if (result.resultCode == Activity.RESULT_OK) {
                apply(plan)
            } else {
                // The user declined; report "nothing renamed, nothing failed".
                onFinished(RenameOutcome(renamed = 0))
            }
        }

    /** Applies [plan], asking for write access once if the platform demands it. */
    fun apply(plan: List<RenamePlanItem>) {
        scope.launch {
            try {
                onFinished(fileOperations.applyRenamePlan(plan))
            } catch (e: WriteConsentRequired) {
                pending = plan
                consentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
            }
        }
    }
}

/**
 * Batch rename form.
 *
 * The plan is recomputed from [RenamePlanner] on every keystroke, so the preview
 * is always exactly what Apply will do. Validation runs against the real
 * directory listing before anything is written.
 */
class BatchRenameDialog : DialogFragment() {

    private var binding: DialogBatchRenameBinding? = null

    private lateinit var renamer: RenameRunner

    /** Original file names and tags of the selected rows. */
    private var targets: List<RenameTarget> = emptyList()

    private val paths: List<String>
        get() = arguments?.getStringArrayList(ARG_PATHS).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Registered here: activity-result launchers must exist before the
        // fragment is started.
        renamer = RenameRunner(this, lifecycleScope, ::report)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val views = DialogBatchRenameBinding.inflate(layoutInflater)
        binding = views

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        }
        views.inputPrefix.addTextChangedListener(watcher)
        views.inputSuffix.addTextChangedListener(watcher)
        views.inputFind.addTextChangedListener(watcher)
        views.inputReplace.addTextChangedListener(watcher)
        views.switchNumbering.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        views.switchUseTags.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        lifecycleScope.launch {
            targets = AudioCatApp.get().fileOperations.renameTargetsFor(paths)
            refreshPreview()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_title)
            .setView(views.root)
            .setNegativeButton(R.string.action_cancel, null)
            // Replaced in onStart so a validation error keeps the dialog open.
            .setPositiveButton(R.string.rename_apply, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)
            ?.getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setOnClickListener { confirm() }
    }

    /** Recomputes the plan and shows the first few renames it would make. */
    private fun refreshPreview() {
        val views = binding ?: return
        val changed = RenamePlanner.plan(targets, currentPattern()).filter { it.changed }
        views.renamePreview.text = buildString {
            changed.take(PREVIEW_LINES).forEach { item ->
                append(item.from.substringAfterLast('/'))
                append(" → ")
                append(item.fileName)
                append('\n')
            }
            if (changed.size > PREVIEW_LINES) append('…')
        }
    }

    private fun confirm() {
        val views = binding ?: return
        val plan = RenamePlanner.plan(targets, currentPattern())
        if (plan.none { it.changed }) {
            dismiss()
            return
        }
        lifecycleScope.launch {
            val existing = AudioCatApp.get().fileOperations.existingPathsFor(paths)
            val error = RenamePlanner.validate(plan, existing)
            if (error != null) {
                Snackbar.make(views.root, error, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            renamer.apply(plan)
        }
    }

    private fun currentPattern(): RenamePattern {
        val views = binding
        return RenamePattern(
            prefix = views?.inputPrefix?.text?.toString().orEmpty(),
            suffix = views?.inputSuffix?.text?.toString().orEmpty(),
            find = views?.inputFind?.text?.toString().orEmpty(),
            replaceWith = views?.inputReplace?.text?.toString().orEmpty(),
            numbering = views?.switchNumbering?.isChecked == true,
            useTags = views?.switchUseTags?.isChecked == true,
        )
    }

    private fun report(outcome: RenameOutcome) {
        val views = binding ?: return
        val message = when {
            outcome.failures.isNotEmpty() -> getString(R.string.rename_failed, outcome.failures.size)
            outcome.renamed > 0 -> getString(R.string.rename_applied, outcome.renamed)
            // Nothing renamed and nothing failed: the consent prompt was denied.
            else -> null
        }
        if (message != null) {
            Snackbar.make(views.root, message, Snackbar.LENGTH_LONG).show()
        }
        if (outcome.renamed > 0) dismiss()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "BatchRenameDialog"

        private const val ARG_PATHS = "paths"

        /** Longest preview list before it is truncated with an ellipsis. */
        private const val PREVIEW_LINES = 12

        fun newInstance(paths: List<String>): BatchRenameDialog = BatchRenameDialog().apply {
            arguments = bundleOf(ARG_PATHS to ArrayList(paths))
        }
    }
}
