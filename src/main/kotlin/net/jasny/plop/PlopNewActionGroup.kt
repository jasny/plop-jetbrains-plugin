package net.jasny.plop

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Action group that provides the "Plop" submenu under the New menu.
 *
 * For this step, the group builds its children dynamically at runtime from a
 * small hardcoded list of fake generators. No Node or plop integration yet.
 * The group is only visible/enabled when there is an open project.
 */
class PlopNewActionGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        // Obtain the project and fetch generators from the project-level service.
        val project = e?.project ?: return emptyArray()

        val service = project.getService(PlopGeneratorService::class.java) ?: return emptyArray()
        val generators = service.getGenerators()

        if (generators.isEmpty()) {
            // If we've already initialized (completed a refresh) but list is empty, show 'No generators found'.
            // Otherwise show a disabled 'Loading…' placeholder while background refresh runs.
            return if (service.isInitialized()) arrayOf(PlopNoGeneratorsAction()) else arrayOf(PlopLoadingAction())
        }

        return generators
            .map { PlopFakeGeneratorAction(it.name, it.description) }
            .toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        // Only show the submenu when a project is open.
        val hasProject = e.project != null
        e.presentation.isEnabledAndVisible = hasProject
    }
}
