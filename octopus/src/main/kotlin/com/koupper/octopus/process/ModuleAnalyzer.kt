package com.koupper.octopus.process

import com.koupper.container.app
import com.koupper.octopus.modules.validateScript
import com.koupper.orchestrator.config.JobConfig
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.JSONFileHandlerImpl
import com.koupper.shared.octopus.extractExportFunctionSignature
import java.io.File

class ModuleAnalyzer(private val context: String, private vararg val flags: String) : Process {
    private var name: String = "init.kts"
    private var type: String = "Not Set"
    private var target: String = "Not Set"

    override fun processName() = this.name
    override fun processType() = this.type

    fun target(target: String) = apply { this.target = target }

    override fun run() {
        val baseDir = File(target)

        val folders = analyzeFolders(baseDir)

        val files = analyzeFiles(baseDir)

        val data = mapOf(
            "folders" to folders,
            "files" to files
        )

        val outputFile = File(System.getProperty("user.home"), ".koupper/helpers/module-analysis.json")
        outputFile.parentFile.mkdirs()
        val textJsonParser = app.getInstance(JSONFileHandler::class) as JSONFileHandlerImpl<Map<String, Any?>>
        outputFile.writeText(textJsonParser.toJsonString(data))
    }

    private fun analyzeFolders(baseDir: File): List<Map<String, Any?>> {
        val projects = mutableListOf<Map<String, Any?>>()

        val handlerRegex = Regex("""class\s+\w+\s*:\s*(Setup|RequestHandler<.*>)""")
        val controllerRegex = Regex("""@Path\(""")

        val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        for (dir in dirs) {
            val tags = mutableSetOf<String>()

            val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
            val kotlinFiles = allFiles.filter { it.extension == "kt" || it.extension == "kts" }

            if (File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists()) {
                tags.add("[proj]")
            }

            val onlyKts = kotlinFiles.isNotEmpty() && kotlinFiles.all { it.extension == "kts" }
            val hasInitKts = kotlinFiles.isNotEmpty() && kotlinFiles.all { it.name == "init.kts" }
            val hasKt = kotlinFiles.any { it.extension == "kt" }
            val hasKts = kotlinFiles.any { it.extension == "kts" }

            val hasConfig = allFiles.any { it.extension == "yml" || it.extension == "yaml" }

            if (onlyKts) {
                tags.add("[kts folder]")

                if (hasInitKts) {
                    tags.add("[init]")
                }

                if (hasConfig) {
                    tags.add("[cfg]")
                }
            } else {
                if (hasKt) tags.add("[kt]")
                if (hasKts) tags.add("[kts]")
                if (hasConfig) tags.add("[cfg]")
            }

            val hasHandler = kotlinFiles.any { file ->
                val content = file.readText()
                handlerRegex.containsMatchIn(content)
            }
            if (hasHandler) tags.add("[hndlrs]")

            val hasController = kotlinFiles.any { file ->
                val content = file.readText()
                controllerRegex.containsMatchIn(content)
            }
            if (hasController) tags.add("[ctrls]")

            if (tags.isNotEmpty()) {
                projects.add(
                    mapOf(
                        "folder" to dir.name,
                        "tags" to tags.toList()
                    )
                )
            }
        }

        return projects
    }

    private fun analyzeFiles(baseDir: File): List<Map<String, Any?>> {
        val files = mutableListOf<Map<String, Any?>>()

        val scripts = baseDir.listFiles { file -> file.extension == "kt" || file.extension == "kts" } ?: emptyArray()

        val hasGradleFile = baseDir.listFiles()?.any {
            it.name == "build.gradle" || it.name == "build.gradle.kts"
        } == true

        if (hasGradleFile) {
            val mca = ModuleControllerAnalyzer()
            mca.analyzeControllers(baseDir)
        }

        for (file in baseDir.listFiles() ?: emptyArray()) {
            val tags = mutableSetOf<String>()
            var signature: String? = null

            when {
                file.name == "init.kts" -> tags.add("[init]")
                file.name == "jobs.json" -> {
                    try {
                        val config = JobConfig.loadOrFail()
                        tags.add("[cfg][driver:${config.driver}]|[queue:${config.queue}]")
                    } catch (e: Exception) {
                        // Silenciar el error y no agregar tags
                        // Opcional: logging.debug("No se pudo cargar JobConfig: ${e.message}")
                    }
                }
                file.name == "$name.http.json" || file.name == "$name.http.yml" || file.name == "$name.http.yaml" -> tags.add("[http config]")
                file.extension in listOf("yml", "yaml", "json") -> tags.add("[cfg]")
                file.extension == "env" -> tags.add("[envs]")
                file.extension == "kt" -> tags.add("[kt]")
                file.extension == "kts" -> tags.add("[kts]")
            }

            if (file in scripts) {
                val validation = validateScript(file.path)
                if (validation.isSuccess) {
                    val scriptContent = file.readText()
                    val functionSignature = extractExportFunctionSignature(scriptContent)
                    if (functionSignature != null) {
                        val (params, returnType) = functionSignature
                        tags.add("[script]")
                        signature = "(${params.joinToString(", ")}) -> $returnType"
                    }
                }
            }

            if (tags.isNotEmpty()) {
                files.add(
                    mapOf(
                        "file" to file.name,
                        "tags" to tags.toList(),
                        "signature" to signature
                    )
                )
            }
        }

        return files
    }
}

