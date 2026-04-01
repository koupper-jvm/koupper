package com.koupper.cli.commands

import com.koupper.cli.ANSIColors
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.CommandManager
import com.koupper.cli.commands.AvailableCommands.NEW
import java.io.File
import java.io.InputStream

class NewCommand : Command() {
    init {
        super.name = NEW
        super.usage =
            "\n   koupper ${ANSI_GREEN_155}$name$ANSI_RESET ${ANSI_GREEN_155}module name=\"auth-server\",version=\"1.0.0\",package=\"tdn.auth\" --script-inclusive \"scripts/example.kts\" type=\"script\"${ANSI_RESET}\n" +
                    "\n   koupper ${ANSI_GREEN_155}$name$ANSI_RESET ${ANSI_GREEN_155}module name=\"auth-server\",version=\"1.0.0\",package=\"tdn.auth\" -si \"scripts/example.kts\" type=\"script\"${ANSI_RESET}\n" +
                    "\n   koupper ${ANSI_GREEN_155}$name$ANSI_RESET ${ANSI_GREEN_155}module name=\"jobs-server\",version=\"1.0.0\",package=\"tdn.jobs\" template=\"jobs\"${ANSI_RESET}\n" +
                    "\n   koupper ${ANSI_GREEN_155}$name$ANSI_RESET ${ANSI_GREEN_155}module name=\"pipeline-server\",version=\"1.0.0\",package=\"tdn.pipeline\" template=\"pipelines\"${ANSI_RESET}\n" +
                    "\n   koupper ${ANSI_GREEN_155}$name$ANSI_RESET ${ANSI_GREEN_155}script-name.kts${ANSI_RESET}\n"
        super.description = "\n   Creates a module or script\n"
        super.arguments = emptyMap()
        super.additionalInformation = """
   For more info: https://koupper.com/cli/commands/new
        """
    }

    override fun name(): String = NEW

    override fun execute(vararg args: String): String {
        var result = when {
            args.size < 2 -> {
                return this.showNewInfo()
            }

            args[1].trim().equals("module", ignoreCase = true) -> {
                val raw = args.drop(2).joinToString(" ").trim()

                val params = ScriptImportParser.parseKeyValueParams(raw)
                val missing = validateRequiredParams(params, listOf("name", "version", "package"))
                if (missing.isNotEmpty()) {
                    return "\n${ANSI_YELLOW_229}Missing required parameters: ${missing.joinToString(", ")}.$ANSI_RESET\n"
                }

                val name = params["name"]!!
                val version = params["version"]!!
                val packageName = params["package"]!!
                val template = (params["template"] ?: "default").trim().ifBlank { "default" }.lowercase()
                val typeRaw = (params["type"] ?: inferTypeFromTemplate(template)).trim().ifBlank { inferTypeFromTemplate(template) }
                val type = normalizeType(typeRaw)

                val allowedTemplates = setOf("default", "http", "jobs", "pipelines")
                if (template !in allowedTemplates) {
                    return "\n${ANSI_YELLOW_229}Invalid template: $template. Allowed: ${allowedTemplates.joinToString(", ")}.$ANSI_RESET\n"
                }

                val allowedTypes = setOf("script", "job", "pipeline")
                if (type !in allowedTypes) {
                    return "\n${ANSI_YELLOW_229}Invalid type: $typeRaw. Allowed: ${allowedTypes.joinToString(", ")} (also supports aliases jobs/pipelines/scripts).$ANSI_RESET\n"
                }

                val tokens = ScriptImportParser.splitBySpacesRespectingQuotes(raw)
                val scriptImports = ScriptImportParser.parseScriptImports(tokens)

                val scriptErrors = ScriptImportParser.validateScriptImports(scriptImports)
                if (scriptErrors.isNotEmpty()) {
                    return "\n${ANSI_YELLOW_229}${scriptErrors.joinToString("\n")}$ANSI_RESET\n"
                }

                val moduleDir = File(args[0], name)

                val initResource = initResourceForTemplate(template)

                val finalInitFile = File(args[0], "init.kts")
                if (!finalInitFile.exists()) finalInitFile.createNewFile()
                this::class.java.classLoader.getResourceAsStream(initResource)?.toFile(finalInitFile.absolutePath)
                    ?: return "\n${ANSI_YELLOW_229}Missing template resource: $initResource.$ANSI_RESET\n"

                val finalScriptContent = finalInitFile.readText(Charsets.UTF_8)
                val replacedInit = finalScriptContent
                    .replace("%MODULE_NAME%", name)
                    .replace("%MODULE_VERSION%", version)
                    .replace("%MODULE_PACKAGE%", packageName)
                    .replace("%MODULE_TYPE%", type)
                    .replace("%MODULE_TEMPLATE%", template.uppercase())
                    .replace("%HANDLER_NAME%", "main")

                finalInitFile.writeText(replacedInit, Charsets.UTF_8)

                val runResult = try {
                    CommandManager.commands["run"]?.execute(moduleDir.parentFile.absolutePath, "init.kts") ?: ""
                } finally {
                    if (finalInitFile.exists()) {
                        finalInitFile.delete()
                    }
                }

                if (!moduleDir.exists()) {
                    return "\n${ANSI_YELLOW_229}Module scaffolding failed for $name. Run output: $runResult$ANSI_RESET\n"
                }

                val requiredScaffoldFiles = listOf(
                    File(moduleDir, "settings.gradle"),
                    File(moduleDir, "build.gradle")
                )
                val missingScaffoldFiles = requiredScaffoldFiles.filterNot { it.exists() }
                if (missingScaffoldFiles.isNotEmpty()) {
                    val missingNames = missingScaffoldFiles.joinToString(", ") { it.name }
                    return "\n${ANSI_YELLOW_229}Module scaffolding failed for $name. Missing files: $missingNames.$ANSI_RESET\n"
                }

                val pkgPath = packageName.trim().replace(".", "/")
                val extensionsDir = File(moduleDir, "src/main/kotlin/$pkgPath/extensions")
                extensionsDir.mkdirs()

                val currentDir = File(args[0])

                applyScriptImports(
                    currentDir = currentDir,
                    moduleExtensionsDir = extensionsDir,
                    type = type,
                    imports = scriptImports,
                    packageName = packageName
                )

                if (!extensionsDir.exists() || extensionsDir.listFiles().isNullOrEmpty()) {
                    return "\n${ANSI_YELLOW_229}Module was created but no starter scripts were generated in ${extensionsDir.path}.$ANSI_RESET\n"
                }

                "Module $name generated successfully with type $type."
            }

            "file:init" in args[1] -> {
                val currentDirectory = args[0]
                val finalScript = currentDirectory + File.separator + "init.kts"

                if (File(finalScript).exists()) {
                    return "\n${ANSI_YELLOW_229} The script ${File(finalScript).name} already exist.${ANSI_RESET}\n"
                }

                this::class.java.classLoader.getResourceAsStream("init.txt")?.toFile(finalScript)
                "init.kts file created."
            }

            ".kts" in args[1].trim() || ".kt" in args[1].trim() -> {
                val currentDirectory = args[0]
                val finalScript = currentDirectory + File.separator + args[1]

                if (File(finalScript).exists()) {
                    return "\n${ANSI_YELLOW_229} The script ${File(finalScript).name} already exist.${ANSI_RESET}\n"
                }

                val template = this::class.java.classLoader.getResourceAsStream("script.txt")
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText()
                    .orEmpty()

                val standaloneTemplate = template
                    .replace("package %PACKAGE%\r\n\r\n", "")
                    .replace("package %PACKAGE%\n\n", "")

                File(finalScript).writeText(standaloneTemplate, Charsets.UTF_8)
                "${args[1]} file created."
            }

            else -> {
                "\n${ANSI_YELLOW_229} The file must end with [.kts] extension or use ${ANSIColors.ANSI_WHITE}koupper new module [${ANSI_GREEN_155}nameOfModule${ANSIColors.ANSI_WHITE}]$ANSI_YELLOW_229|| koupper new [config-type].$ANSI_RESET\n"
            }
        }

        val env = File(".env")
        if (!env.exists()) {
            env.createNewFile()
            result += "\n${ANSI_YELLOW_229}An file .env was created to keep the scripts configurations$ANSI_RESET\n"
        }

        return result
    }

    private fun InputStream.toFile(path: String) {
        File(path).outputStream().use { this.copyTo(it) }
    }

    private fun showNewInfo(): String {
        val additionalInfo = this.showAdditionalInformation()

        val newInfo = """
            
               You can create:
            $ANSI_YELLOW_229
               1.- Module: A gradle project containing scripts, resources, and configurations to manage your development.  
               2.- Script: A simple script to do something.
            $ANSI_RESET
               Use the command$ANSI_YELLOW_229 koupper help new$ANSI_RESET for more information.
               
        """.trimIndent()

        return "$newInfo$additionalInfo$ANSI_RESET"
    }

    override fun showArguments(): String {
        return """

 ${ANSIColors.ANSI_YELLOW_229}* Parameters:$ANSI_RESET
   ${ANSI_GREEN_155}name$ANSI_RESET      Required module name
   ${ANSI_GREEN_155}version$ANSI_RESET   Required semantic version
   ${ANSI_GREEN_155}package$ANSI_RESET   Required Kotlin package (e.g. demo.app)
   ${ANSI_GREEN_155}template$ANSI_RESET  Optional: default | http | jobs | pipelines
   ${ANSI_GREEN_155}type$ANSI_RESET      Optional: script | job | pipeline (aliases supported)

 ${ANSIColors.ANSI_YELLOW_229}* Script import flags:$ANSI_RESET
   ${ANSI_GREEN_155}-si, --script-inclusive$ANSI_RESET           Include script preserving relative path
   ${ANSI_GREEN_155}-se, --script-exclusive$ANSI_RESET           Include script at module root extensions
   ${ANSI_GREEN_155}-swi, --script-wildcard-inclusive$ANSI_RESET Include all scripts from wildcard preserving structure
   ${ANSI_GREEN_155}-swe, --script-wildcard-exclusive$ANSI_RESET Include all scripts from wildcard flattened

        """.trimIndent()
    }

    private fun applyScriptImports(
        currentDir: File,
        moduleExtensionsDir: File,
        type: String,
        imports: List<ParsedScriptImport>,
        packageName: String
    ) {
        val templates = templateResourceForType(type)

        for ((index, template) in templates.withIndex()) {
            val fileName = if (templates.size > 1) {
                "script${index + 1}.kt"
            } else {
                "script.kt"
            }

            val baseScript = File(moduleExtensionsDir, fileName)

            val inputStream = this::class.java.classLoader.getResourceAsStream(template)

            requireNotNull(inputStream) {
                "Template resource not found: $template for type: $type"
            }

            inputStream.use { input ->
                baseScript.parentFile.mkdirs()
                baseScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val baseContent = baseScript.readText(Charsets.UTF_8)
            val baseReplaced = baseContent.replace("%PACKAGE%", "${packageName}.extensions")
            baseScript.writeText(baseReplaced, Charsets.UTF_8)

            require(baseScript.exists() && baseScript.isFile && baseScript.length() > 0) {
                "Failed to create base script file: ${baseScript.absolutePath}"
            }
        }

        val baseExtensionsDir = File(moduleExtensionsDir, "extensions")

        imports.forEach { imp ->
            if (imp.wildcard) {
                val baseDirRel = imp.path.substringBefore("*").trimEnd('/')
                val baseDirFs = File(currentDir, baseDirRel)
                require(baseDirFs.exists() && baseDirFs.isDirectory) {
                    "Wildcard directory not found: ${imp.path}"
                }

                val files = baseDirFs.listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".kts") || it.name.endsWith(".kt")) }
                    .orEmpty()

                if (files.isEmpty()) {
                    println("${ANSI_YELLOW_229}Warning: No .kts or .kt files found in wildcard path: ${imp.path}$ANSI_RESET")
                }

                files.forEach { src ->
                    val dest = computeDestination(moduleExtensionsDir, src, baseExtensionsDir, imp.mode)
                    dest.parentFile.mkdirs()
                    require(src.exists() && src.isFile) {
                        "Source file not found or is not a file: ${src.absolutePath}"
                    }

                    val content = src.readText(Charsets.UTF_8)
                    val contentReplaced = content.replace("%PACKAGE%", packageName)
                    dest.writeText(contentReplaced, Charsets.UTF_8)

                    require(dest.exists() && dest.length() > 0) {
                        "Failed to copy file: ${src.name} to ${dest.absolutePath}"
                    }
                }
            } else {
                val srcFs = File(currentDir, imp.path)
                require(imp.path.isNotBlank()) {
                    "Import path cannot be empty"
                }

                val dest = computeDestination(moduleExtensionsDir, srcFs, baseExtensionsDir, imp.mode)
                dest.parentFile.mkdirs()

                if (srcFs.exists() && srcFs.isFile) {
                    val content = srcFs.readText(Charsets.UTF_8)
                    val contentReplaced = content.replace("%PACKAGE%", packageName)
                    dest.writeText(contentReplaced, Charsets.UTF_8)

                    require(dest.exists() && dest.length() > 0) {
                        "Failed to copy file: ${srcFs.name} to ${dest.absolutePath}"
                    }
                } else {
                    println("${ANSI_YELLOW_229}Resource specified in path is not a file.${ANSI_RESET}")
                }
            }
        }
    }

    private fun computeDestination(
        moduleScriptsDir: File,
        sourceFile: File,
        baseExtensionsDir: File,
        mode: ScriptImportMode
    ): File {
        val relativePath = sourceFile.relativeTo(baseExtensionsDir).path

        return when (mode) {
            ScriptImportMode.EXCLUSIVE -> {
                File(moduleScriptsDir, sourceFile.name)
            }
            ScriptImportMode.INCLUSIVE -> {
                File(moduleScriptsDir, relativePath)
            }
        }
    }

    private fun templateResourceForType(type: String): List<String> {
        return when (normalizeType(type)) {
            "script" -> listOf("script.txt")
            "job" -> listOf("job.txt")
            "pipeline" -> listOf("script1.txt", "script2.txt")
            else -> listOf("script.txt")
        }
    }

    private fun normalizeType(type: String): String {
        return when (type.trim().lowercase()) {
            "scripts" -> "script"
            "jobs" -> "job"
            "pipelines" -> "pipeline"
            else -> type.trim().lowercase()
        }
    }

    private fun inferTypeFromTemplate(template: String): String {
        return when (template.trim().lowercase()) {
            "jobs" -> "job"
            "pipelines" -> "pipeline"
            else -> "script"
        }
    }

    private fun validateRequiredParams(params: Map<String, String>, required: List<String>): List<String> {
        return required.filter { params[it].isNullOrBlank() }
    }

    private fun initResourceForTemplate(template: String): String {
        return when (template.trim().lowercase()) {
            "default" -> "init.txt"
            "http" -> "init-http.txt"
            "jobs" -> "init-jobs.txt"
            "pipelines" -> "init-pipelines.txt"
            else -> "init.txt"
        }
    }
}
