package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Sweeper fork: request a next-edit suggestion now, at the current cursor position.
 *
 * Upstream only requests a suggestion after a document edit (debounced) or a caret move
 * within `cursor-movement-threshold` (60 s) of the last edit. This action bypasses both
 * and the debounce. Unlike [ClearAutocompleteRejectionCacheAction] it keeps the rejection
 * cache, so suggestions dismissed with Escape stay dismissed.
 *
 * Default keystroke: Alt+Backslash (Windows/Linux), Option+Backslash (Mac).
 */
class TriggerEditCompletionAction : AnAction() {
    companion object {
        const val ACTION_ID = "dev.sweep.assistant.autocomplete.edit.TriggerEditCompletionAction"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null && !project.isDisposed && event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        RecentEditsTracker.getInstance(project).processLatestEdit()
    }
}
