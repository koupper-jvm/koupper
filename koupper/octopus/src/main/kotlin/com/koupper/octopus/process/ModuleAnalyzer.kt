package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_CYAN
import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN
import com.koupper.configurations.utilities.ANSIColors.ANSI_RED
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.app
import com.koupper.octopus.modifiers.ControllersAnalyzer
import com.koupper.octopus.modules.validateScript
import com.koupper.orchestrator.config.JobConfig
import com.koupper.shared.monitoring.ExecutionMonitor
import com.koupper.shared.octopus.extractExportFunctionSignature
import java.io.File

class ModuleAnalyzer(private val context: String) : Process {
    private var name: String = "ModuleAnalyzer"
    private var type: String = "GRYZZLY2_GRADLE_JERSEY"
    private var target: String = "Not Set"

    override fun processName() = name
    override fun processType() = type

    fun target(target: String) = apply { this.target = target }

    override fun run() {
        val baseDir = File(target)

        val folders = analyzeFolders(baseDir)
        val files = analyzeFiles(baseDir)

        val version = getVersionFrom(baseDir)
        val basePackage = detectProjectBasePackage(baseDir) ?: "❌ Not found"

        val handlers = listHandlerSourceFiles(baseDir)
        val impls = handlers.filter { isKHandlerImplementation(it) }
        val implNames = impls.flatMap { extractKHandlerImplementationNames(it) }.toSet()

        val handlersStatus = if (handlers.isNotEmpty() && impls.isNotEmpty()) "✔️" else "❌"

        val port = runCatching { extractServerPort(baseDir)?.toInt() }.getOrNull()
        if (port != null) {
            ControllersAnalyzer().analyzeControllers(baseDir, port = port)
        }

        val info = buildString {
            appendLine("${ANSI_CYAN}📦 Module Setup Info:${ANSI_RESET}")
            appendLine("  ${ANSI_YELLOW_229}- Target${ANSI_RESET}        : ${baseDir.path}")
            appendLine("  ${ANSI_YELLOW_229}- Type${ANSI_RESET}          : $type")
            appendLine("  ${ANSI_YELLOW_229}- Version${ANSI_RESET}       : $version")
            appendLine("  ${ANSI_YELLOW_229}- Base package${ANSI_RESET}  : $basePackage")
            appendLine("")
            appendLine("  ${ANSI_YELLOW_229}- Handlers${ANSI_RESET}      : $handlersStatus")
            appendLine("      • Total in package   : ${handlers.size}")
            appendLine("      • KHandler impls     : ${impls.size}")
            appendLine("")

            appendLine("  🗂️ Module Structure:")
            appendLine("")

            if (implNames.isEmpty()) {
                appendLine("      ${ANSI_RED}No handlers found${ANSI_RESET}")
            } else {
                appendLine("      ${ANSI_YELLOW_229}Detected:${ANSI_RESET}")
                implNames.sorted().forEach { n ->
                    appendLine("        ✔️ ${ANSI_GREEN}$n${ANSI_RESET}")
                }
            }
        }

        val data = mapOf(
            "folders" to folders,
            "files" to files,
            "more_info" to info
        )

        app.getInstance(com.koupper.shared.monitoring.ExecutionMonitor::class).reportPayload("module-analysis", data)
    }

    private fun stripKotlinComments(src: String): String {
        var s = src.replace(Regex("(?s)/\\*.*?\\*/"), "")
        s = s.replace(Regex("(?m)//.*$"), "")
        return s
    }

    private data class KotlinTypeDecl(
        val name: String,
        val isData: Boolean,
        val header: String
    )

    private fun findTypeDecls(src: String): List<KotlinTypeDecl> {
        val s = stripKotlinComments(src)
        val out = mutableListOf<KotlinTypeDecl>()
        val declRegex = Regex("(?m)\\b(data\\s+)?(class|object)\\s+([A-Za-z_]\\w*)\\b")

        declRegex.findAll(s).forEach { m ->
            val isData = m.groupValues[1].isNotBlank()
            val name = m.groupValues[3]
            val start = m.range.first
            val brace = s.indexOf('{', m.range.last + 1)
            if (brace == -1) return@forEach
            val header = s.substring(start, brace)
            out.add(KotlinTypeDecl(name, isData, header))
        }

        return out
    }

    private fun headerImplementsKHandler(header: String): Boolean {
        val idx = header.indexOf(':')
        if (idx == -1) return false
        val supertypes = header.substring(idx + 1)
        return Regex("(^|[^\\w.])KHandler\\b").containsMatchIn(supertypes)
    }

    private fun isKHandlerImplementation(file: File): Boolean {
        val decls = findTypeDecls(file.readText())
        return decls.any { !it.isData && headerImplementsKHandler(it.header) }
    }

    private fun extractKHandlerImplementationNames(file: File): List<String> {
        val decls = findTypeDecls(file.readText())
        return decls
            .filter { !it.isData }
            .filter { headerImplementsKHandler(it.header) }
            .map { it.name }
            .distinct()
    }

    private fun listHandlerSourceFiles(baseDir: File): List<File> {
        val src = File(baseDir, "src/main/kotlin")
        if (!src.exists()) return emptyList()

        return src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.parentFile?.name == "handlers" && it.parentFile?.parentFile?.name == "http" }
            .toList()
    }

    private fun analyzeFolders(baseDir: File): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        val handlerRegex = Regex("class\\s+\\w+\\s*:\\s*(Setup|RequestHandler<.*>)")
        val controllerRegex = Regex("@Path\\(")
        val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

        dirs.forEach { dir ->
            val tags = mutableSetOf<String>()
            val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
            val kotlinFiles = allFiles.filter { it.extension == "kt" || it.extension == "kts" }

            if (File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists()) tags.add("[proj]")
            if (kotlinFiles.any { it.extension == "kt" }) tags.add("[kt]")
            if (kotlinFiles.any { it.extension == "kts" }) tags.add("[kts]")
            if (allFiles.any { it.extension in listOf("yml", "yaml", "json") }) tags.add("[cfg]")
            if (kotlinFiles.any { handlerRegex.containsMatchIn(it.readText()) }) tags.add("[hndlrs]")
            if (kotlinFiles.any { controllerRegex.containsMatchIn(it.readText()) }) tags.add("[ctrls]")

            if (tags.isNotEmpty()) {
                result.add(mapOf("folder" to dir.name, "tags" to tags.toList()))
            }
        }

        return result
    }

    private fun analyzeFiles(baseDir: File): List<Map<String, Any?>> {
        val files = mutableListOf<Map<String, Any?>>()

        baseDir.listFiles()?.forEach { file ->
            val tags = mutableSetOf<String>()
            var signature: String? = null

            when {
                file.name == "init.kts" -> tags.add("[init]")
                file.name == "jobs.json" -> {
                    try {
                        val config = JobConfig.loadOrFail(context = baseDir.path)
                        val configs = config.configurations ?: emptyList()
                        if (configs.size == 1) {
                            tags.add("[cfg] [id: ${configs.first().id}]")
                        } else {
                            tags.add("[cfg] [${configs.size} configurations]")
                        }
                    } catch (_: Exception) {}
                }
                file.extension == "kt" -> tags.add("[kt]")
                file.extension == "kts" -> tags.add("[kts]")
                file.extension in listOf("yml", "yaml", "json") -> tags.add("[cfg]")
            }

            if (file.extension in listOf("kt", "kts")) {
                val validation = validateScript(file.path)
                if (validation.isSuccess) {
                    extractExportFunctionSignature(file.readText())?.let { sig ->
                        tags.add("[script]")
                        signature = "(${sig.parameterTypes.joinToString(", ")}) -> ${sig.returnType}"
                    }
                }
            }

            if (tags.isNotEmpty()) {
                files.add(mapOf("file" to file.name, "tags" to tags.toList(), "signature" to signature))
            }
        }

        return files
    }

    private fun getVersionFrom(moduleDir: File): String {
        val f = File(moduleDir, "build.gradle").takeIf { it.exists() }
            ?: File(moduleDir, "build.gradle.kts").takeIf { it.exists() }
            ?: return "❌ No build.gradle"

        return Regex("""\bversion\s*=\s*["'](.+?)["']""")
            .find(f.readText())
            ?.groupValues?.get(1)
            ?: "❌ Version not found"
    }

    private fun detectProjectBasePackage(baseDir: File): String? {
        readGradleGroup(baseDir)?.let { return it }
        return detectMostCommonSourcePackage(baseDir)
    }

    private fun readGradleBuildFile(baseDir: File): String? {
        val groovy = File(baseDir, "build.gradle")
        val kts = File(baseDir, "build.gradle.kts")
        return when {
            groovy.exists() -> groovy.readText()
            kts.exists() -> kts.readText()
            else -> null
        }
    }

    private fun readGradleGroup(baseDir: File): String? {
        val c = readGradleBuildFile(baseDir) ?: return null
        return Regex("""\bgroup\s*=\s*["'](.+?)["']""")
            .find(c)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
    }

    private fun detectMostCommonSourcePackage(baseDir: File): String? {
        val roots = listOf(
            File(baseDir, "src/main/kotlin"),
            File(baseDir, "src/main/java")
        ).filter { it.exists() }

        val regex = Regex("""^\s*package\s+([a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)*)""")
        val pkgs = mutableListOf<String>()

        roots.forEach { r ->
            r.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { f ->
                    f.useLines { lines ->
                        lines.take(80).forEach { l ->
                            regex.find(l)?.groupValues?.get(1)?.let { p -> pkgs.add(p) }
                        }
                    }
                }
        }

        return pkgs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}
