package net.jasny.plop

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

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
        val project = e?.project ?: return emptyArray()

        // Resolve context dir from the event and then the closest package root with both package.json and plopfile.*
        val contextDir: VirtualFile? = resolveContextDirectory(e)
        val projectBase = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val plopRoot: VirtualFile? = resolveClosestPackageRoot(contextDir, projectBase) ?: projectBase

        val rootPath = plopRoot?.path ?: return emptyArray()

        val service = project.getService(PlopGeneratorService::class.java) ?: return emptyArray()
        val generators = service.getGeneratorsForRoot(rootPath)

        if (generators.isEmpty()) {
            // Show loading until this root has been initialized at least once
            return if (service.isInitializedForRoot(rootPath)) arrayOf(PlopNoGeneratorsAction()) else arrayOf(PlopLoadingAction())
        }

        return generators
            .map { PlopDescribeGeneratorAction(it.name, it.description, rootPath) }
            .toTypedArray()
    }

    private fun resolveContextDirectory(e: AnActionEvent): VirtualFile? {
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project
        if (vf != null) {
            return if (vf.isDirectory) vf else vf.parent
        }
        val base = project?.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(base)
    }

    private fun resolveClosestPackageRoot(start: VirtualFile?, stopAt: VirtualFile?): VirtualFile? {
        if (start == null) return null
        val supported = setOf(
            "plopfile.mts", "plopfile.cts", "plopfile.ts",
            "plopfile.mjs", "plopfile.cjs", "plopfile.js"
        )

        var dir: VirtualFile? = start
        while (dir != null) {
            val hasPkg = dir.findChild("package.json")?.isValid == true
            val hasPlop = supported.any { name -> dir.findChild(name)?.isValid == true }
            if (hasPkg && hasPlop) return dir
            if (dir == stopAt) break
            dir = dir.parent
        }
        return null
    }

    override fun update(e: AnActionEvent) {
        // Only show the submenu when a project is open.
        val hasProject = e.project != null
        e.presentation.isEnabledAndVisible = hasProject
    }
}
