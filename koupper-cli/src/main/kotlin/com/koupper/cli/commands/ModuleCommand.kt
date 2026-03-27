package com.koupper.cli.commands

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.CommandManager
import com.koupper.cli.commands.AvailableCommands.MODULE
import com.koupper.cli.modules.ApiConfig
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

fun extractServerPort(moduleDir: File): String? {
    val setupFile = findKtFileRecursively(
        File(moduleDir, "src/main/kotlin"),
        "Setup"
    ) ?: return null

    val content = setupFile.readText()

    return Regex("""const val PORT\s*=\s*(\d+)""")
        .find(content)
        ?.groupValues?.get(1)
}

fun findKtFileRecursively(baseDir: File, fileName: String): File? {
    if (!baseDir.exists()) return null
    return baseDir.walkTopDown().find {
        it.isFile && it.name == "$fileName.kt"
    }
}

class ModuleCommand : Command() {
    private lateinit var currentLocation: File

    init {
        super.name = MODULE
        super.usage = """
            
   koupper $ANSI_GREEN_155$name$ANSI_RESET
    """
        super.description = """
   ${ANSI_YELLOW_229}Displays folders and files inside the module, including their tags.
   For scripts, it also includes function return types (signatures).
   If job configurations are present, they are displayed as well.
   If the module is a Gradle project, it detects HTTP controllers and lists their endpoints and handlers.

   ${ANSI_YELLOW_229}Also displays detailed module-level information, such as:${ANSI_RESET}
     - HTTP contract configuration from the YML file
     - Synchronization status of scripts and handlers with the module
     - Synchronization between the HTTP contract and the module's implementation${ANSI_RESET}
    """
        super.arguments = emptyMap()
        super.additionalInformation = """
   For more info: https://koupper.com/cli/commands/module
    """
    }

    private val icons = mapOf(
        "[init]" to "\u001B[35m[init]\u001B[0m",
        "[cfg]" to "\u001B[36m[cfg]\u001B[0m",
        "[envs]" to "\u001B[33m[envs]\u001B[0m",
        "[kt]" to "\u001B[32m[kt]\u001B[0m",
        "[kts]" to "\u001B[33m[kts]\u001B[0m",
        "[proj]" to "\u001B[34m[proj]\u001B[0m",
        "[kts folder]" to "\u001B[33m[kts folder]\u001B[0m",
        "[hndlrs]" to "\u001B[34m[handlers]\u001B[0m",
        "[ctrls]" to "\u001B[31m[ctrls]\u001B[0m",
        "[script]" to "\u001B[36m[script]\u001B[0m",
        "[file]"    to "\u001B[90m[driver: file]\u001B[0m",
        "[sqs]"    to "\u001B[90m[driver: sqs]\u001B[0m",
        "[default]" to "\u001B[37m[queue: default]\u001B[0m"
    )

    private val labelColor = mapOf(
        "driver" to "\u001B[94m",
        "queue"  to "\u001B[94m",
    )

    private val tokenRegex = Regex("""\[(.*?)\]""")

    override fun name(): String = MODULE

    override fun execute(vararg args: String): String {
        val flags = mutableSetOf<String>()
        var moduleName: String? = null

        for (arg in args.drop(1)) {
            if (arg.startsWith("-")) flags += arg else { moduleName = arg; break }
        }

        val rawCurrent = args.getOrNull(0) ?: "."
        currentLocation = File(rawCurrent).absoluteFile
        val libDir = File(currentLocation, "libs")

        val octopusJar = libDir.listFiles { f ->
            f.isFile && f.name.startsWith("octopus-") && f.name.endsWith(".jar")
        }?.maxByOrNull { it.lastModified() }

        val octopusDependencyInfo = if (octopusJar != null) {
            val name = octopusJar.name
            val version = name.removePrefix("octopus-").removeSuffix(".jar")
            "📦 Octopus dependency: $name (version $version)\n"
        } else {
            "⚠️ Octopus dependency not found in ${libDir.absolutePath}\n"
        }

        val results = mutableListOf<String>()
        val targetDir = if (moduleName != null) File(currentLocation, moduleName) else currentLocation
        if (!targetDir.exists() || !targetDir.isDirectory) {
            return "\n$ANSI_YELLOW_229 Module not found: ${targetDir.path} $ANSI_RESET\n"
        }

        val koupperHelpersDirectory = System.getProperty("user.home") + File.separator +
                ".koupper" + File.separator + "helpers" + File.separator
        val finalScript = "${koupperHelpersDirectory}list.kts"

        this::class.java.classLoader.getResourceAsStream("list.txt")?.toFile(finalScript)
        val finalScriptContent = File(finalScript).readText(Charsets.UTF_8)

        val escapedPath = targetDir.path.replace("\\", "\\\\")
        val replacedScript = finalScriptContent.replace("%TARGET%", escapedPath)
        File(finalScript).writeText(replacedScript, Charsets.UTF_8)

        CommandManager.commands["run"]?.execute(koupperHelpersDirectory, "list.kts") ?: ""

        results += buildModuleAnalysisResult()

        results += buildModuleControllersResult()

        results += describeHttpConfig()

        if (results.isEmpty()) {
            return "\n$octopusDependencyInfo\n" +
                    "$ANSI_YELLOW_229 No info produced for this module.$ANSI_RESET\n"
        }

        return "\n$octopusDependencyInfo\n" + results.joinToString("\n")
    }

    private fun buildModuleAnalysisResult(): String {
        val result = StringBuilder()

        val home = System.getProperty("user.home")
        val moduleJson = File(home, ".koupper/helpers/module-analysis.json")
        if (!moduleJson.exists()) return ""

        val mapper = jacksonObjectMapper()
        val raw: Any = mapper.readValue(moduleJson.inputStream(), object : TypeReference<Any>() {})

        val jsonData: Map<String, Any?> = when (raw) {
            is Map<*, *> -> raw.entries.associate { (k, v) -> k.toString() to v }
            is List<*> -> {
                val first = raw.firstOrNull()
                if (first is Map<*, *>) first.entries.associate { (k, v) -> k.toString() to v }
                else emptyMap()
            }
            else -> emptyMap()
        }

        if (jsonData.isEmpty()) {
            moduleJson.delete()
            return ""
        }

        val moreInfo = jsonData["more_info"]?.toString()?.trim().orEmpty()
        if (moreInfo.isNotBlank()) {
            result.append(moreInfo)
            if (!moreInfo.endsWith("\n")) result.append("\n")
            result.append("\n")
        }

        val folders = jsonData["folders"] as? List<Map<String, Any?>> ?: emptyList()
        val files = jsonData["files"] as? List<Map<String, Any?>> ?: emptyList()

        val allNames = folders.map { it["folder"] as? String ?: "" } +
                files.map { it["file"] as? String ?: "" }

        val maxNameLength = allNames.maxOfOrNull { it.length } ?: 0

        for (folder in folders) {
            val folderName = folder["folder"] as? String ?: ""
            val tags = (folder["tags"] as? List<String>)?.map(::colorizeTag) ?: emptyList()
            val padded = folderName.padEnd(maxNameLength + 2)
            result.append("$padded${tags.joinToString(" ")}\n")
        }

        for (file in files) {
            val fileName = file["file"] as? String ?: ""
            val tags = (file["tags"] as? List<String>)?.map(::colorizeTag) ?: emptyList()
            val signature = file["signature"]?.toString().orEmpty()
            val padded = fileName.padEnd(maxNameLength + 2)
            result.append("$padded${tags.joinToString(" ")}")
            if (signature.isNotBlank()) result.append(" $signature")
            result.append("\n")
        }

        moduleJson.delete()
        return result.toString()
    }

    private fun colorizeTag(tag: String): String =
        tokenRegex.replace(tag) { m ->
            val token = m.value
            icons[token]?.let { return@replace it }

            val inner = m.groupValues[1]
            val idx = inner.indexOf(':')
            if (idx >= 0) {
                val labelRaw = inner.substring(0, idx).trim()
                val labelKey = labelRaw.lowercase()
                val value = inner.substring(idx + 1).trim()
                val color = labelColor[labelKey]
                if (color != null && value.isNotEmpty()) {
                    return@replace "[$labelRaw: ${color}$value\u001B[0m]"
                }
            }

            token
        }

    private fun InputStream.toFile(path: String) {
        File(path).outputStream().use { this.copyTo(it) }
    }

    private fun describeHttpConfig(): String {
        val sb = StringBuilder()
        val GREEN = "\u001B[32m"
        val RED = "\u001B[31m"
        val YELLOW = "\u001B[33m"
        val RESET = "\u001B[0m"
        val HEADER_COLOR = "\u001B[36m"

        val pattern = Regex(""".*\.http\.(json|ya?ml)$""")
        val candidate = currentLocation.listFiles()?.firstOrNull { it.name.matches(pattern) }
            ?: return "❌ No se encontró ningún archivo .http.json o .http.yml en $currentLocation \n"

        val yaml = Yaml()
        val config = yaml.loadAs(candidate.inputStream(), ApiConfig::class.java)

        sb.append("\n${HEADER_COLOR} ☁️ Http configuration:${RESET}\n\n")
        sb.append("Config:\n")
        sb.append("  Config version: ${config.version}\n")
        sb.append("  Description: ${config.description}\n\n")

        val objectMapper = jacksonObjectMapper()
        val controllersFile = File(System.getProperty("user.home"), ".koupper/helpers/controllers.json")
        val controllersData = objectMapper.readValue<List<Map<String, Any?>>>(controllersFile)

        val jsonController = controllersData.firstOrNull()
        val jsonContextPath = jsonController?.get("contextPath")?.toString()
        val jsonPort = jsonController?.get("port")?.toString()

        val yamlContextPath = config.server?.contextPath
        val yamlPort = config.server?.port?.toString()

        val contextPathStatus = when {
            yamlContextPath == jsonContextPath -> "$yamlContextPath ${GREEN}✔️$RESET"
            else -> "$yamlContextPath ${RED}❌ Differs from $jsonContextPath$RESET"
        }

        val portStatus = when {
            jsonPort == null -> "$yamlPort ${YELLOW}(No port in JSON)$RESET"
            yamlPort == jsonPort -> "$yamlPort $GREEN✔️$RESET"
            else -> "$yamlPort $RED❌ Differs from $jsonPort$RESET"
        }

        sb.append("Server:\n")
        sb.append("  Port: $portStatus\n")
        sb.append("  Context path: $contextPathStatus\n\n")

        sb.append("Controllers:\n")

        val controllersList = jsonController?.get("controllers") as? List<Map<String, Any?>> ?: emptyList()
        val yamlControllers = config.controllers ?: emptyList()

        yamlControllers.forEach { yamlCtrl ->
            val yamlCtrlName = yamlCtrl.name
            val yamlCtrlPath = yamlCtrl.path?.trimEnd('/') ?: ""

            val jsonCtrl = controllersList.find { it["controller"] == yamlCtrlName && it["path"]?.toString()?.trimEnd('/') == yamlCtrlPath }
            val ctrlMatchStatus = if (jsonCtrl != null) "$GREEN✔️$RESET" else "$RED❌$RESET"

            sb.append("$yamlCtrlName -> $yamlCtrlPath $ctrlMatchStatus\n")

            val jsonEndpoints = jsonCtrl?.get("endpoints") as? List<Map<String, Any?>> ?: emptyList()

            yamlCtrl.apis?.forEachIndexed { idx, api ->
                val handlerName = "RequestHandler" + (api.handler?.replaceFirstChar { it.uppercaseChar() } ?: "")

                val matchedEndpoint = jsonEndpoints.find {
                    it["path"]?.toString()?.trim() == api.path?.trim() &&
                            it["method"]?.toString()?.uppercase() == api.method?.trim()?.uppercase() &&
                            it["handler"]?.toString() == api.handler &&
                            it["function"]?.toString() == api.name
                }

                val matchStatus = if (matchedEndpoint != null) "$GREEN✔️$RESET" else "$RED❌$RESET"

                sb.append("  - API #${idx + 1}: $matchStatus\n")
                sb.append("      Name: ${api.name ?: "N/A"}\n")
                sb.append("      Path: ${api.path ?: "N/A"}\n")
                sb.append("      Method: ${api.method ?: "N/A"}\n")
                sb.append("      Handler: $handlerName\n")
                sb.append("      Description: ${api.description ?: "N/A"}\n")

                val consumes = matchedEndpoint?.get("consumes")?.toString() ?: "application/json"
                val produces = matchedEndpoint?.get("produces")?.toString() ?: "application/json"

                sb.append("      Consumes: $consumes\n")
                sb.append("      Produces: $produces\n")
            }
        }

        return sb.toString()
    }

    private fun buildModuleControllersResult(): String {
        val result = StringBuilder()
        val GREEN = "\u001B[32m"
        val CYAN = "\u001B[36m"
        val YELLOW = "\u001B[33m"
        val RESET = "\u001B[0m"
        val DIM = "\u001B[2m"

        val file = File(System.getProperty("user.home"), ".koupper/helpers/controllers.json")
        if (!file.exists()) return "⚠️  No controllers found\n"

        try {
            val mapper = jacksonObjectMapper()
            val raw: Any = mapper.readValue(file, object : com.fasterxml.jackson.core.type.TypeReference<Any>() {})

            val data: Map<String, Any?> = when (raw) {
                is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
                is List<*> -> mapOf("controllers" to raw)
                else -> emptyMap()
            }

            val moreInfo = data["more_info"]?.toString()?.trim().orEmpty()
            if (moreInfo.isNotBlank()) {
                result.append(moreInfo)
                if (!moreInfo.endsWith("\n")) result.append("\n")
                result.append("\n")
            }

            val controllers = data["controllers"] as? List<Map<String, Any?>> ?: emptyList()
            if (controllers.isEmpty()) return result.toString()

            result.append(" ⚙️ Controllers found:\n\n")

            val allEndpoints = controllers.flatMap { it["endpoints"] as? List<Map<String, Any?>> ?: emptyList() }
            val maxMethod = (allEndpoints.maxOfOrNull { (it["method"]?.toString() ?: "Unknown").length } ?: 6).coerceAtLeast(6)
            val maxHandler = (allEndpoints.maxOfOrNull { (it["handler"]?.toString() ?: "Unknown").length } ?: 7).coerceAtLeast(7)

            controllers.forEachIndexed { idx, entry ->
                val port = entry["port"] ?: "Unknown"
                val name = entry["controller"] as? String ?: "Unknown"
                val basePath = entry["path"] ?: "/"
                val endpoints = entry["endpoints"] as? List<Map<String, Any?>> ?: emptyList()

                if (idx > 0) {
                    result.append("${DIM}────────────────────────────────────────────────────────────${RESET}\n\n")
                }

                result.append("🔹 Controller: ${CYAN}$name$RESET (port ${YELLOW}$port$RESET, base path: ${YELLOW}$basePath$RESET)\n\n")

                if (endpoints.isEmpty()) {
                    result.append("   └ No endpoints found.\n\n")
                } else {
                    endpoints.forEach { endpoint ->
                        val method = (endpoint["method"]?.toString() ?: "Unknown")
                        val endpointPath = (endpoint["path"]?.toString() ?: "Unknown")
                        val consumes = (endpoint["consumes"]?.toString() ?: "None")
                        val produces = (endpoint["produces"]?.toString() ?: "None")
                        val function = (endpoint["function"]?.toString() ?: "Unknown")
                        val handler = (endpoint["handler"]?.toString() ?: "Unknown")

                        val methodPad = method.padEnd(maxMethod)
                        val handlerPad = handler.padEnd(maxHandler)

                        result.append(
                            """
   └ ${GREEN}${methodPad}$RESET ${YELLOW}${endpointPath}$RESET  ${DIM}handler:${RESET} ${CYAN}${handlerPad}$RESET
       ↳ fn: ${CYAN}${function}$RESET
                        """.trimIndent()
                        )

                        if (consumes != "None" || produces != "None") {
                            result.append(
                                """
                            
       ↳ io: ${YELLOW}${consumes}$RESET → ${YELLOW}${produces}$RESET
                            """.trimIndent()
                            )
                        }

                        result.append("\n\n")
                    }
                }
            }

            return result.toString().trimEnd() + "\n"
        } finally {
            if (file.exists()) file.delete()
        }
    }
}
