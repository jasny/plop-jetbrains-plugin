package net.jasny.plop

import com.google.gson.Gson
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Action that, when invoked, describes the selected Plop generator, shows a prompts dialog,
 * and logs the collected answers. It does not execute the generator yet.
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
                        log.info("Plop answers for '$generatorName': ${Gson().toJson(answers)}")
                    } else {
                        log.debug("Plop prompts dialog cancelled for '$generatorName'")
                    }
                }
            }
        }.queue()
    }
}
