package net.jasny.plop

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * A disabled placeholder action shown when no Plop generators are found
 * after the background discovery has completed.
 */
class PlopNoGeneratorsAction : AnAction("No generators found"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        // no-op
    }

    override fun update(e: AnActionEvent) {
        // Keep it visible but disabled
        e.presentation.isEnabled = false
        e.presentation.isVisible = true
    }
}
