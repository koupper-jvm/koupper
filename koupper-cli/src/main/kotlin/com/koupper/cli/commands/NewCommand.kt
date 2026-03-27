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

                val params = parseKeyValueParams(raw)
                val missing = validateRequiredParams(params, listOf("name", "version", "package"))
                if (missing.isNotEmpty()) {
                    return "\n${ANSI_YELLOW_229}Missing required parameters: ${missing.joinToString(", ")}.$ANSI_RESET\n"
                }

                val name = params["name"]!!
                val version = params["version"]!!
                val packageName = params["package"]!!
                val type = (params["type"] ?: "script").trim().ifBlank { "script" }
                val template = (params["template"] ?: "default").trim().ifBlank { "default" }

                val allowedTemplates = setOf("default", "http", "jobs", "pipelines")
                if (template !in allowedTemplates) {
                    return "\n${ANSI_YELLOW_229}Invalid template: $template. Allowed: ${allowedTemplates.joinToString(", ")}.$ANSI_RESET\n"
                }

                val tokens = splitBySpacesRespectingQuotes(raw)
                val scriptImports = parseScriptImports(tokens)

                val scriptErrors = validateScriptImports(scriptImports)
                if (scriptErrors.isNotEmpty()) {
                    return "\n${ANSI_YELLOW_229}${scriptErrors.joinToString("\n")}$ANSI_RESET\n"
                }

                val moduleDir = File(args[0], name)
                moduleDir.mkdirs()

                val pkgPath = packageName.trim().replace(".", "/")
                val extensionsDir = File(moduleDir, "src/main/kotlin/$pkgPath/extensions")
                extensionsDir.mkdirs()

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

                val currentDir = File(args[0])

                applyScriptImports(
                    currentDir = currentDir,
                    moduleExtensionsDir = extensionsDir,
                    type = type,
                    imports = scriptImports,
                    packageName = packageName
                )

                CommandManager.commands["run"]?.execute(moduleDir.parentFile.absolutePath, "init.kts") ?: ""

                if (finalInitFile.exists()) {
                    finalInitFile.delete()
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

                this::class.java.classLoader.getResourceAsStream("script.txt")?.toFile(finalScript)
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

    private enum class ScriptMode { INCLUSIVE, EXCLUSIVE }

    private data class ScriptImport(
        val mode: ScriptMode,
        val wildcard: Boolean,
        val path: String
    )

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

    override fun showArguments(): String = ""

    private fun parseKeyValueParams(input: String): Map<String, String> {
        if (input.isBlank()) return emptyMap()

        val regex = Regex("""\b([A-Za-z][A-Za-z0-9_-]*)\s*=\s*("([^"]*)"|([^\s,]+))""")
        val out = LinkedHashMap<String, String>()

        regex.findAll(input).forEach { m ->
            val key = m.groupValues[1].trim()
            val quoted = m.groupValues[3]
            val plain = m.groupValues[4]
            val value = if (quoted.isNotBlank()) quoted else plain
            out[key] = value
        }

        return out
    }

    private fun splitBySpacesRespectingQuotes(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in input) {
            when (ch) {
                '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }

                ' ' -> {
                    if (inQuotes) sb.append(ch)
                    else {
                        val token = sb.toString().trim()
                        if (token.isNotEmpty()) out.add(token)
                        sb.setLength(0)
                    }
                }

                else -> sb.append(ch)
            }
        }

        val last = sb.toString().trim()
        if (last.isNotEmpty()) out.add(last)

        return out
    }

    private fun parseScriptImports(tokens: List<String>): List<ScriptImport> {
        fun stripQuotes(s: String): String =
            if (s.length >= 2 && s.first() == '"' && s.last() == '"') s.substring(1, s.length - 1) else s

        fun flagToImport(flag: String): Pair<ScriptMode, Boolean>? = when (flag) {
            "-si", "--script-inclusive" -> ScriptMode.INCLUSIVE to false
            "-se", "--script-exclusive" -> ScriptMode.EXCLUSIVE to false
            "-swi", "--script-wildcard-inclusive" -> ScriptMode.INCLUSIVE to true
            "-swe", "--script-wildcard-exclusive" -> ScriptMode.EXCLUSIVE to true
            else -> null
        }

        val out = mutableListOf<ScriptImport>()
        var i = 0

        while (i < tokens.size) {
            val flag = tokens[i].trim()
            val mapped = flagToImport(flag)

            if (mapped != null) {
                val (mode, wildcard) = mapped
                val next = tokens.getOrNull(i + 1) ?: throw IllegalArgumentException("Missing path after $flag")
                val path = stripQuotes(next.trim())
                out.add(ScriptImport(mode, wildcard, path))
                i += 2
            } else {
                i += 1
            }
        }

        return out
    }

    private fun validateScriptImports(imports: List<ScriptImport>): List<String> {
        val errors = mutableListOf<String>()

        imports.forEach { imp ->
            if (imp.path.isBlank()) {
                errors.add("Empty script path")
                return@forEach
            }
            if (!imp.path.startsWith("extensions/")) {
                errors.add("Script path must start with extensions/: ${imp.path}")
            }
            if (!imp.wildcard) {
                if (!(imp.path.endsWith(".kts") || imp.path.endsWith(".kt"))) {
                    errors.add("Script must end with .kts or .kt: ${imp.path}")
                }
            } else {
                if (!imp.path.contains("*")) {
                    errors.add("Wildcard flag requires * in path: ${imp.path}")
                }
            }
        }

        return errors
    }

    private fun applyScriptImports(
        currentDir: File,
        moduleExtensionsDir: File,
        type: String,
        imports: List<ScriptImport>,
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
                    println("${ANSI_YELLOW_229}Resource specified in path is not a file.")
                }
            }
        }
    }

    private fun computeDestination(
        moduleScriptsDir: File,
        sourceFile: File,
        baseExtensionsDir: File,
        mode: ScriptMode
    ): File {
        val relativePath = sourceFile.relativeTo(baseExtensionsDir).path

        return when (mode) {
            ScriptMode.EXCLUSIVE -> {
                File(moduleScriptsDir, sourceFile.name)
            }
            ScriptMode.INCLUSIVE -> {
                File(moduleScriptsDir, relativePath)
            }
        }
    }

    private fun templateResourceForType(type: String): List<String> {
        return when (type.trim().lowercase()) {
            "script" -> listOf("script.txt")
            "job" -> listOf("job.txt")
            "pipeline" -> listOf("script1.txt", "script2.txt")
            else -> listOf("script.txt")
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