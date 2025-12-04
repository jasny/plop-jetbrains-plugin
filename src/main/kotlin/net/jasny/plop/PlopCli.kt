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
        // Detect plopfile and decide how to run Node (TS loader, ESM bridge handled by helper)
        val plopInfo = detectPlopfile(projectDir)
        if (plopInfo == null) {
            if (log.isDebugEnabled) log.debug("No plopfile found in $projectDir")
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
            return emptyList()
        }

        val nodeParams = buildNodeParamsForTs(nodeExecutable, projectDir, plopInfo)

        val commandLine = GeneralCommandLine(nodeExecutable)
            .withParameters(nodeParams + listOf(scriptFile.absolutePath, projectDir, plopInfo.path, plopInfo.moduleKind))
            .withWorkDirectory(projectDir)

        if (log.isDebugEnabled) {
            log.debug(
                "About to execute Node script to list plop generators: " +
                    "node='${nodeExecutable}', script='${scriptFile.absolutePath}', workDir='${projectDir}', plop='${plopInfo.path}', kind='${plopInfo.moduleKind}', params='${nodeParams.joinToString(" ")}'"
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

    /** Result of running a generator. */
    data class RunResult(
        val success: Boolean,
        val message: String,
        val changedPaths: List<String> = emptyList()
    )

    /**
     * Run a plop generator programmatically by invoking a Node helper script.
     * Returns a RunResult with success=false and a human-readable message on any failure.
     */
    fun runGenerator(projectDir: String, generatorName: String, answers: Map<String, Any?>): RunResult {
        val scriptFile = try {
            extractResource("plop/run-generator.js", "run-generator.js")
        } catch (e: Exception) {
            log.warn("Failed to extract run-generator.js resource", e)
            return RunResult(false, "Failed to prepare Plop runner script")
        }

        val nodeExecutable = resolveNodeInterpreterPath() ?: run {
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
            return RunResult(false, "Node.js interpreter not configured")
        }

        val plopInfo = detectPlopfile(projectDir) ?: run {
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
            return RunResult(false, "No plopfile found in project")
        }

        val nodeParams = buildNodeParamsForTs(nodeExecutable, projectDir, plopInfo)

        val answersJson = try {
            Gson().toJson(answers)
        } catch (t: Throwable) {
            log.warn("Failed to serialize answers to JSON", t)
            "{}"
        }

        val commandLine = GeneralCommandLine(nodeExecutable)
            .withParameters(nodeParams + listOf(scriptFile.absolutePath, projectDir, plopInfo.path, plopInfo.moduleKind, generatorName, answersJson))
            .withWorkDirectory(projectDir)

        if (log.isDebugEnabled) {
            log.debug(
                "About to execute Node script to run plop generator: " +
                    "node='${nodeExecutable}', script='${scriptFile.absolutePath}', workDir='${projectDir}', gen='${generatorName}', plop='${plopInfo.path}', kind='${plopInfo.moduleKind}', params='${nodeParams.joinToString(" ")}'"
            )
        }

        return try {
            val output = ExecUtil.execAndGetOutput(commandLine)

            if (log.isDebugEnabled) {
                log.debug(
                    "Node run script finished: exitCode=${output.exitCode}, " +
                        "stdout.length=${output.stdout.length}, stderr.length=${output.stderr.length}"
                )
                if (output.stderr.isNotBlank()) {
                    log.debug("Node run script stderr:\n${output.stderr}")
                }
                if (output.stdout.isBlank()) {
                    log.debug("Node run script stdout is blank")
                } else {
                    val preview = if (output.stdout.length > 1000) output.stdout.substring(0, 1000) + "..." else output.stdout
                    log.debug("Node run script stdout (preview up to 1000 chars):\n$preview")
                }
            }

            if (output.exitCode != 0) {
                log.warn("Run script exited with non-zero code: ${output.exitCode}. stderr=${output.stderr}. stdout=${output.stdout}")
                RunResult(false, "Plop generator failed to run (exit ${output.exitCode}). See logs for details.")
            } else {
                parseRunResultJson(output.stdout)
            }
        } catch (t: Throwable) {
            log.warn("Failed to execute Node to run generator '$generatorName'", t)
            RunResult(false, "Failed to execute Node to run generator")
        } finally {
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
        }
    }

    private fun parseRunResultJson(json: String): RunResult {
        return try {
            val gson = Gson()
            val obj = gson.fromJson(json, java.util.LinkedHashMap::class.java) as? Map<*, *> ?: emptyMap<String, Any?>()
            val ok = (obj["ok"] as? Boolean) ?: (obj["success"] as? Boolean) ?: false
            val msg = (obj["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: if (ok) "Plop: generator completed" else "Plop: generator failed"
            val changesAny = obj["changes"]
            val changedPaths: List<String> = when (changesAny) {
                is Collection<*> -> changesAny.mapNotNull { it?.toString() }
                is Array<*> -> changesAny.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            RunResult(ok, msg, changedPaths)
        } catch (e: Exception) {
            log.warn("Failed to parse run result JSON", e)
            RunResult(false, "Invalid JSON from Plop run script")
        }
    }

    /**
     * Describe a specific generator's prompts by invoking a Node helper script.
     * Runs the process and returns a DTO. On any error, returns an empty description with no prompts.
     */
    fun describeGenerator(projectDir: String, generatorName: String): PlopGeneratorDescription {
        val scriptFile = try {
            extractResource("plop/describe-generator.js", "describe-generator.js")
        } catch (e: Exception) {
            log.warn("Failed to extract describe-generator.js resource", e)
            return PlopGeneratorDescription(name = generatorName, description = "", prompts = emptyList())
        }

        val nodeExecutable = resolveNodeInterpreterPath() ?: run {
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
            return PlopGeneratorDescription(name = generatorName, description = "", prompts = emptyList())
        }

        val plopInfo = detectPlopfile(projectDir)
        if (plopInfo == null) {
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
            return PlopGeneratorDescription(name = generatorName, description = "", prompts = emptyList())
        }

        val nodeParams = buildNodeParamsForTs(nodeExecutable, projectDir, plopInfo)

        val commandLine = GeneralCommandLine(nodeExecutable)
            .withParameters(nodeParams + listOf(scriptFile.absolutePath, projectDir, plopInfo.path, plopInfo.moduleKind, generatorName))
            .withWorkDirectory(projectDir)

        if (log.isDebugEnabled) {
            log.debug(
                "About to execute Node script to describe plop generator: " +
                    "node='${nodeExecutable}', script='${scriptFile.absolutePath}', workDir='${projectDir}', gen='${generatorName}', plop='${plopInfo.path}', kind='${plopInfo.moduleKind}', params='${nodeParams.joinToString(" ")}'"
            )
        }

        return try {
            val output = ExecUtil.execAndGetOutput(commandLine)

            if (log.isDebugEnabled) {
                log.debug(
                    "Node describe script finished: exitCode=${output.exitCode}, " +
                        "stdout.length=${output.stdout.length}, stderr.length=${output.stderr.length}"
                )
                if (output.stderr.isNotBlank()) {
                    log.debug("Node describe script stderr:\n${output.stderr}")
                }
                if (output.stdout.isBlank()) {
                    log.debug("Node describe script stdout is blank")
                } else {
                    val preview = if (output.stdout.length > 1000) output.stdout.substring(0, 1000) + "..." else output.stdout
                    log.debug("Node describe script stdout (preview up to 1000 chars):\n$preview")
                }
            }

            if (output.exitCode != 0) {
                log.warn("Describe script exited with non-zero code: ${output.exitCode}. stderr=${output.stderr}. stdout=${output.stdout}")
                PlopGeneratorDescription(name = generatorName, description = "", prompts = emptyList())
            } else {
                val desc = parseGeneratorDescriptionJson(output.stdout)
                if (log.isDebugEnabled) {
                    log.debug("Parsed ${desc.prompts.size} prompts for generator '${desc.name}'")
                }
                desc
            }
        } catch (t: Throwable) {
            log.warn("Failed to execute Node to describe generator '$generatorName'", t)
            PlopGeneratorDescription(name = generatorName, description = "", prompts = emptyList())
        } finally {
            try { FileUtil.delete(scriptFile) } catch (_: Throwable) {}
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

    private fun extractResource(resourcePath: String, fileName: String): File {
        val url = javaClass.classLoader.getResource(resourcePath)
            ?: throw IllegalStateException("Resource not found: $resourcePath")
        val input: InputStream = url.openStream()
        val tempDir = FileUtil.createTempDirectory("plop-script", null, true)
        val file = File(tempDir, fileName)
        input.use { ins ->
            file.outputStream().use { out ->
                ins.copyTo(out)
            }
        }
        return file
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

    // DTOs for generator description
    data class PlopGeneratorDescription(
        val name: String,
        val description: String,
        val prompts: List<Prompt>
    )

    data class Prompt(
        val type: String?,
        val name: String?,
        val message: String?,
        val default: com.google.gson.JsonElement?,
        val choices: com.google.gson.JsonElement?
    )

    private fun parseGeneratorDescriptionJson(json: String): PlopGeneratorDescription {
        if (json.isBlank()) return PlopGeneratorDescription(name = "", description = "", prompts = emptyList())
        return try {
            val gson = Gson()
            val dto = gson.fromJson(json, GeneratorDescriptionDto::class.java)
            val prompts = dto.prompts?.map {
                Prompt(
                    type = it.type,
                    name = it.name,
                    message = it.message,
                    default = it.default,
                    choices = it.choices
                )
            } ?: emptyList()
            PlopGeneratorDescription(dto.name.orEmpty(), dto.description.orEmpty(), prompts)
        } catch (e: JsonSyntaxException) {
            log.warn("Failed to parse generator description JSON", e)
            PlopGeneratorDescription(name = "", description = "", prompts = emptyList())
        } catch (e: Exception) {
            log.warn("Unexpected error parsing generator description JSON", e)
            PlopGeneratorDescription(name = "", description = "", prompts = emptyList())
        }
    }

    private data class GeneratorDescriptionDto(
        val name: String?, val description: String?, val prompts: List<PromptDto>?
    )
    private data class PromptDto(
        val type: String?, val name: String?, val message: String?,
        val default: com.google.gson.JsonElement?, val choices: com.google.gson.JsonElement?
    )

    // --- Plopfile detection and Node runner flags ---

    private data class PlopfileInfo(val path: String, val moduleKind: String, val isTs: Boolean)

    private fun detectPlopfile(projectDir: String): PlopfileInfo? {
        val candidates = listOf(
            "plopfile.mts",
            "plopfile.cts",
            "plopfile.ts",
            "plopfile.mjs",
            "plopfile.cjs",
            "plopfile.js",
        )
        val base = File(projectDir)
        val file = candidates.map { File(base, it) }.firstOrNull { it.isFile }
            ?: return null

        val ext = file.extension.lowercase()
        return when (ext) {
            "cjs" -> PlopfileInfo(file.absolutePath, "cjs", false)
            "mjs" -> PlopfileInfo(file.absolutePath, "esm", false)
            "cts" -> PlopfileInfo(file.absolutePath, "cjs", true)
            "mts" -> PlopfileInfo(file.absolutePath, "esm", true)
            "ts" -> {
                val kind = nearestPackageType(File(projectDir))
                PlopfileInfo(file.absolutePath, if (kind == "module") "esm" else "cjs", true)
            }
            "js" -> {
                val kind = nearestPackageType(File(projectDir))
                PlopfileInfo(file.absolutePath, if (kind == "module") "esm" else "cjs", false)
            }
            else -> PlopfileInfo(file.absolutePath, "cjs", false)
        }
    }

    private fun nearestPackageType(startDir: File): String {
        var dir: File? = startDir
        while (dir != null) {
            val pkg = File(dir, "package.json")
            if (pkg.isFile) {
                return try {
                    val text = pkg.readText()
                    val type = com.google.gson.JsonParser.parseString(text).asJsonObject.get("type")?.asString
                    if (type == "module") "module" else "commonjs"
                } catch (_: Throwable) {
                    "commonjs"
                }
            }
            dir = dir.parentFile
        }
        return "commonjs"
    }

    private fun buildNodeParamsForTs(nodeExecutable: String, projectDir: String, info: PlopfileInfo): List<String> {
        if (!info.isTs) return emptyList()
        // Prefer tsx --loader for any TS plopfile so helpers can import TS directly
        val tsx = resolveNodePackageUpwards(projectDir, "tsx")
        if (tsx != null) {
            val major = getNodeMajorVersion(nodeExecutable)
            // Node 22+ switched to --import for loaders like tsx
            return if (major >= 22) listOf("--import", "tsx") else listOf("--loader", "tsx")
        }
        // Fallback to ts-node/register/transpile-only
        val tsNode = resolveNodePackageUpwards(projectDir, "ts-node")
        if (tsNode != null) {
            return listOf("-r", "ts-node/register/transpile-only")
        }
        // Fallback to @swc/register
        val swc = resolveNodePackageUpwards(projectDir, "@swc/register")
        if (swc != null) {
            return listOf("-r", "@swc/register")
        }
        // No loader found; run without flags (helpers will likely fail gracefully)
        return emptyList()
    }

    private fun getNodeMajorVersion(nodeExecutable: String): Int {
        return try {
            val cmd = GeneralCommandLine(nodeExecutable).withParameters("--version")
            val out = ExecUtil.execAndGetOutput(cmd)
            val ver = out.stdout.trim() // e.g. v22.3.0
            val cleaned = if (ver.startsWith("v")) ver.substring(1) else ver
            val majorStr = cleaned.takeWhile { it.isDigit() }
            majorStr.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun resolveNodePackageUpwards(projectDir: String, packageName: String): File? {
        var dir: File? = File(projectDir)
        while (dir != null) {
            val pkgDir = if (packageName.startsWith("@")) {
                // Scoped package like @swc/register -> node_modules/@swc/register
                File(File(dir, "node_modules"), packageName)
            } else {
                File(File(dir, "node_modules"), packageName)
            }
            val pkgJson = File(pkgDir, "package.json")
            if (pkgJson.isFile) return pkgDir
            dir = dir.parentFile
        }
        return null
    }
}
