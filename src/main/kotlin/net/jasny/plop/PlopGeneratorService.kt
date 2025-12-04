package net.jasny.plop

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level service that provides available Plop generators.
 *
 * Delegates to PlopCli which calls Node and a helper script to inspect the project's
 * plopfile. On any failure, this returns an empty list to keep the UI responsive.
 */
@Service(Service.Level.PROJECT)
class PlopGeneratorService(@Suppress("unused") private val project: Project) {

    private val log: Logger = Logger.getInstance(PlopGeneratorService::class.java)

    @Volatile
    private var cachedGenerators: List<PlopGenerator> = emptyList()

    @Volatile
    private var initialized: Boolean = false

    private val refreshing = AtomicBoolean(false)

    data class PlopGenerator(val name: String, val description: String)

    /**
     * Returns the last known list of generators without blocking the EDT.
     * If no cache is available yet, schedules an asynchronous refresh.
     */
    fun getGenerators(): List<PlopGenerator> {
        val basePath = project.basePath
        if (basePath == null) {
            log.info("No project base path found; returning empty generator list")
            return emptyList()
        }

        // Kick off a background refresh if needed, but always return the current cache immediately.
        if (cachedGenerators.isEmpty()) {
            refreshAsync()
        }
        return cachedGenerators
    }

    /**
     * Returns true once a background refresh has completed at least once
     * (successfully), even if the resulting list is empty.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Refresh the cached generators on a background thread. Multiple concurrent refreshes are coalesced.
     */
    fun refreshAsync() {
        val basePath = project.basePath ?: return
        if (!refreshing.compareAndSet(false, true)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val list = PlopCli(project).listGenerators(basePath)
                cachedGenerators = list
                initialized = true
                if (list.isEmpty()) {
                    log.info("No Plop generators found (or failed to list). Cache set to empty list.")
                }
            } catch (t: Throwable) {
                log.warn("Failed to refresh Plop generators in background", t)
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Clear the cached list and immediately trigger a background refresh.
     * Marks the service as not initialized so the UI can show a loading state.
     */
    fun invalidateAndRefresh() {
        cachedGenerators = emptyList()
        initialized = false
        refreshAsync()
    }
}
