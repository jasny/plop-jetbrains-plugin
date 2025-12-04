package net.jasny.plop

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Warms up the Plop generators cache shortly after project open and
 * watches the plopfile for changes to refresh the list of generators.
 */
class PlopStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.getService(PlopGeneratorService::class.java) ?: return
        // Warm-up on startup
        service.refreshAsync()

        // Watch the project's plopfile and refresh when it changes
        val basePath = project.basePath ?: return
        val normalizedBase = basePath.trimEnd('/', '\\')
        // Watch all supported plopfile variants and package.json (changing `type` may affect resolution)
        val candidates = listOf(
            "$normalizedBase/plopfile.mts",
            "$normalizedBase/plopfile.cts",
            "$normalizedBase/plopfile.ts",
            "$normalizedBase/plopfile.mjs",
            "$normalizedBase/plopfile.cjs",
            "$normalizedBase/plopfile.js",
            "$normalizedBase/package.json"
        )

        val connection = project.messageBus.connect(project)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    // If any event touches a plopfile (any supported variant) or package.json, invalidate and refresh
                    val changed = events.any { e ->
                        val path = e.path
                        // Match exact normalized paths or suffix match to be robust
                        candidates.any { cand -> path == cand || path.endsWith("/" + cand.substringAfterLast('/')) }
                    }
                    if (changed) {
                        service.invalidateAndRefresh()
                    }
                }
            }
        )
    }
}
