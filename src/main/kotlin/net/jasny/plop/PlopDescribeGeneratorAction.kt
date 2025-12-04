package net.jasny.plop

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

/**
 * Action that describes the selected Plop generator, shows a prompts dialog,
 * and runs the generator with the collected answers.
 */
class PlopDescribeGeneratorAction(
    private val generatorName: String,
    description: String? = null
) : AnAction(generatorName, description, null), DumbAware {

    private val log: Logger = Logger.getInstance(PlopDescribeGeneratorAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        object : Task.Backgroundable(project, "Loading Plop generator prompts", false) {
            private lateinit var desc: PlopCli.PlopGeneratorDescription

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    desc = PlopCli(project).describeGenerator(basePath, generatorName)
                } catch (t: Throwable) {
                    log.warn("Failed to describe generator '$generatorName'", t)
                    desc = PlopCli.PlopGeneratorDescription(generatorName, "", emptyList())
                }
            }

            override fun onSuccess() {
                // Show dialog on EDT
                ApplicationManager.getApplication().invokeLater {
                    val dialog = PlopGeneratorDialog(project, desc)
                    val answers = dialog.showAndGetAnswers()
                    if (answers != null) {
                        // Run generator in background
                        object : Task.Backgroundable(project, "Running Plop generator: $generatorName", false) {
                            private lateinit var result: PlopCli.RunResult

                            override fun run(indicator: ProgressIndicator) {
                                indicator.isIndeterminate = true
                                try {
                                    result = PlopCli(project).runGenerator(basePath, generatorName, answers)
                                } catch (t: Throwable) {
                                    log.warn("Failed to run plop generator '$generatorName'", t)
                                    result = PlopCli.RunResult(false, "Failed to run generator: ${t.message ?: "Unknown error"}")
                                }
                            }

                            override fun onSuccess() {
                                // Refresh VFS and show notification on EDT
                                ApplicationManager.getApplication().invokeLater {
                                    try {
                                        val base = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
                                        if (base != null) {
                                            VfsUtil.markDirtyAndRefresh(true, true, true, base)
                                        } else {
                                            // Fallback: global refresh of local FS
                                            LocalFileSystem.getInstance().refresh(true)
                                        }
                                    } catch (t: Throwable) {
                                        log.warn("Failed to refresh VFS after running plop generator", t)
                                    }

                                    val group = NotificationGroupManager.getInstance().getNotificationGroup("Plop Notifications")
                                    if (result.success) {
                                        group.createNotification(result.message, NotificationType.INFORMATION).notify(project)
                                    } else {
                                        group.createNotification(result.message, NotificationType.ERROR).notify(project)
                                    }
                                }
                            }
                        }.queue()
                    } else {
                        log.debug("Plop prompts dialog cancelled for '$generatorName'")
                    }
                }
            }
        }.queue()
    }
}
