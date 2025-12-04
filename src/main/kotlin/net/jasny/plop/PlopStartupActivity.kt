package net.jasny.plop

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Warms up the Plop generators cache shortly after project open.
 * Uses StartupActivity.Background to avoid running on EDT.
 */
class PlopStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        val service = project.getService(PlopGeneratorService::class.java) ?: return
        service.refreshAsync()
    }
}
