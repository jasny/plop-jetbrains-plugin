package net.jasny.plop

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.extensions.PluginId
import java.io.File
import java.io.InputStream

/**
 * PlopCli is responsible for invoking Node with the bundled list-generators.js
 * to inspect a project's plopfile and return available generators.
 *
 * It fails softly: on any error it logs a warning and returns an empty list.
 */
class PlopCli(private val project: Project) {
    private val log: Logger = Logger.getInstance(PlopCli::class.java)

    fun listGenerators(projectDir: String): List<PlopGeneratorService.PlopGenerator> {
        val scriptFile = try {
            extractScriptResource()
        } catch (e: Exception) {
            log.warn("Failed to extract list-generators.js resource", e)
            return emptyList()
        }

        // Resolve Node.js interpreter via the Node.js plugin.
        // If the plugin is missing/disabled or no interpreter is configured, notify the user and fail softly.
        val nodeExecutable = resolveNodeInterpreterPath() ?: run {
            // Cleanup temp script before returning
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
            return emptyList()
        }
        val commandLine = GeneralCommandLine(nodeExecutable)
            .withParameters(listOf(scriptFile.absolutePath, projectDir))
            .withWorkDirectory(projectDir)

        if (log.isDebugEnabled) {
            log.debug(
                "About to execute Node script to list plop generators: " +
                    "node='${nodeExecutable}', script='${scriptFile.absolutePath}', workDir='${projectDir}'"
            )
        }

        return try {
            val output = ExecUtil.execAndGetOutput(commandLine)

            if (log.isDebugEnabled) {
                log.debug(
                    "Node script finished: exitCode=${output.exitCode}, " +
                        "stdout.length=${output.stdout.length}, stderr.length=${output.stderr.length}"
                )
                if (output.stderr.isNotBlank()) {
                    log.debug("Node script stderr:\n${output.stderr}")
                }
                if (output.stdout.isBlank()) {
                    log.debug("Node script stdout is blank")
                } else {
                    val preview = if (output.stdout.length > 1000) output.stdout.substring(0, 1000) + "..." else output.stdout
                    log.debug("Node script stdout (preview up to 1000 chars):\n$preview")
                }
            }

            if (output.exitCode != 0) {
                log.warn("Node script exited with non-zero code: ${output.exitCode}. stderr=${output.stderr}. stdout=${output.stdout}")
                emptyList()
            } else {
                val list = parseGeneratorsJson(output.stdout)
                if (log.isDebugEnabled) {
                    log.debug("Parsed ${list.size} plop generators from script output")
                    if (list.isEmpty()) {
                        log.debug("No generators parsed. Raw JSON stdout (preview up to 1000 chars) shown above.")
                    }
                }
                list
            }
        } catch (t: Throwable) {
            log.warn("Failed to execute Node to list generators", t)
            emptyList()
        } finally {
            // Best-effort cleanup of temp script file
            try {
                FileUtil.delete(scriptFile)
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    /**
     * Uses the Node.js plugin to find the configured Node interpreter for the current project.
     * Shows a user-facing notification if the plugin is missing/disabled, or if no interpreter
     * is configured. Returns the absolute path to the Node executable, or null on failure.
     */
    private fun resolveNodeInterpreterPath(): String? {
        val nodePluginId = PluginId.getId("NodeJS")

        val descriptor = PluginManagerCore.getPlugin(nodePluginId)
        val isInstalledAndEnabled = descriptor != null && descriptor.isEnabled
        if (!isInstalledAndEnabled) {
            log.warn("Node.js plugin is not installed or disabled")
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Plop Notifications")
                .createNotification(
                    "Plop: Node.js plugin is not installed or disabled. Enable it to list Plop generators.",
                    NotificationType.ERROR
                )
                .notify(project)
            return null
        }

        // Access NodeJsInterpreterManager via reflection to avoid a hard compile-time dependency.
        // This keeps the plugin loading even in IDEs without the Node.js plugin.
        return try {
            val mgrClass = Class.forName("com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager")
            val getInstance = mgrClass.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val getInterpreter = mgrClass.getMethod("getInterpreter")
            val interpreter = getInterpreter.invoke(manager) ?: run {
                log.warn("No Node.js interpreter configured for the project")
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Plop Notifications")
                    .createNotification(
                        "Plop: No Node.js interpreter configured. Configure Node.js in settings to use Plop generators.",
                        NotificationType.ERROR
                    )
                    .notify(project)
                return null
            }

            // Try common method names to obtain the interpreter path across IDE versions.
            val candidateMethods = listOf(
                "getInterpreterSystemDependentPath",
                "getSystemDependentPath",
                "getInterpreterPath",
                "getPath",
                "getExecutable",
                "getCommand"
            )

            var path: String? = null
            for (name in candidateMethods) {
                try {
                    val m = interpreter.javaClass.getMethod(name)
                    val value = m.invoke(interpreter) as? String
                    if (!value.isNullOrBlank()) {
                        path = value
                        break
                    }
                } catch (_: NoSuchMethodException) {
                } catch (_: Throwable) {
                }
            }

            if (path.isNullOrBlank()) {
                log.warn("Failed to obtain Node interpreter path from Node.js plugin API")
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Plop Notifications")
                    .createNotification(
                        "Plop: No Node.js interpreter configured. Configure Node.js in settings to use Plop generators.",
                        NotificationType.ERROR
                    )
                    .notify(project)
                null
            } else path
        } catch (e: ClassNotFoundException) {
            // Shouldn't happen if plugin is enabled, but fail softly.
            log.warn("Node.js plugin classes not found", e)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Plop Notifications")
                .createNotification(
                    "Plop: Node.js plugin is not installed or disabled. Enable it to list Plop generators.",
                    NotificationType.ERROR
                )
                .notify(project)
            null
        } catch (t: Throwable) {
            log.warn("Failed to resolve Node interpreter via Node.js plugin", t)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Plop Notifications")
                .createNotification(
                    "Plop: No Node.js interpreter configured. Configure Node.js in settings to use Plop generators.",
                    NotificationType.ERROR
                )
                .notify(project)
            null
        }
    }

    private fun extractScriptResource(): File {
        val resourcePath = "plop/list-generators.js"
        val url = javaClass.classLoader.getResource(resourcePath)
            ?: throw IllegalStateException("Resource not found: $resourcePath")

        val input: InputStream = url.openStream()
        val tempDir = FileUtil.createTempDirectory("plop-script", null, true)
        val scriptFile = File(tempDir, "list-generators.js")
        input.use { ins ->
            scriptFile.outputStream().use { out ->
                ins.copyTo(out)
            }
        }
        return scriptFile
    }

    private fun parseGeneratorsJson(json: String): List<PlopGeneratorService.PlopGenerator> {
        if (json.isBlank()) return emptyList()
        return try {
            val gson = Gson()
            val array = gson.fromJson(json, Array<GeneratorDto>::class.java) ?: return emptyList()
            array.map { PlopGeneratorService.PlopGenerator(it.name.orEmpty(), it.description.orEmpty()) }
        } catch (e: JsonSyntaxException) {
            log.warn("Failed to parse generators JSON", e)
            emptyList()
        } catch (e: Exception) {
            log.warn("Unexpected error parsing generators JSON", e)
            emptyList()
        }
    }

    private data class GeneratorDto(val name: String?, val description: String?)
}
