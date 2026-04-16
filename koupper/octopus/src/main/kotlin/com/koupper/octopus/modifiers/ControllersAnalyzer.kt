package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.shared.monitoring.ExecutionMonitor
import java.io.File

class ControllersAnalyzer {

    fun analyzeControllers(
        moduleDir: File,
        port: Int = 0,
        outputFileName: String = "controllers.json"
    ) {
        val srcRoot = File(moduleDir, "src/main/kotlin")
        if (!srcRoot.exists()) return

        val khandlerIndex = buildKHandlerIndex(moduleDir)
        val fileIndex = buildSimpleFileIndex(moduleDir)

        val controllerFiles = srcRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { isControllerFile(it.readText()) }
            .toList()

        if (controllerFiles.isEmpty()) return

        val allControllersData = mutableListOf<Map<String, Any?>>()

        val controllersData = controllerFiles
            .map { controllerFile ->
                val controllerContent = controllerFile.readText()
                val endpoints = extractControllerInfo(controllerContent, khandlerIndex, fileIndex)
                mapOf(
                    "port" to port,
                    "controller" to controllerFile.nameWithoutExtension,
                    "path" to (extractControllerBasePath(controllerContent) ?: "/"),
                    "endpoints" to endpoints
                )
            }
            .toList()

        allControllersData.addAll(controllersData)

        app.getInstance(com.koupper.shared.monitoring.ExecutionMonitor::class)
            .reportPayload(outputFileName.substringBeforeLast(".json"), allControllersData)
    }

    private fun extractControllerInfo(
        controllerContent: String,
        khandlerIndex: Map<String, String>,
        fileIndex: Map<String, File>
    ): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()

        val methodBlockRegex = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
        val callRegex = Regex(
            """\b(?:this\s*\.\s*)?(\w+)\s*(?:!!)?\s*(?:\?\.|\.)\s*(execute|handleRequest|handle)\s*\("""
        )

        val controllerCtorRegex = Regex("""(?s)\bclass\s+\w+\s*\((.*?)\)\s*[:{]""")
        val controllerPropTypedRegex = Regex(
            """(?m)\b(?:private|public|protected|internal)?\s*val\s+([A-Za-z_]\w*)\s*:\s*([A-Za-z_][\w.<>,?]*)"""
        )
        val controllerPropInitRegex = Regex(
            """(?m)\b(?:private|public|protected|internal)?\s*val\s+([A-Za-z_]\w*)\s*=\s*([A-Z][\w.]*)\s*(?:<[^>]*>)?\s*\("""
        )

        val controllerProps: Map<String, String> = buildMap {
            val ctorParams = controllerCtorRegex.find(controllerContent)?.groupValues?.get(1).orEmpty()
            controllerPropTypedRegex.findAll(ctorParams).forEach { m ->
                val v = m.groupValues[1]
                val t = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                put(v, t)
            }
            controllerPropTypedRegex.findAll(controllerContent).forEach { m ->
                val v = m.groupValues[1]
                val t = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                putIfAbsent(v, t)
            }
            controllerPropInitRegex.findAll(controllerContent).forEach { m ->
                val v = m.groupValues[1]
                val t = m.groupValues[2].substringAfterLast('.').substringBefore('<').trim()
                putIfAbsent(v, t)
            }
        }

        val inFunctionInitRegex = Regex(
            """(?m)\bval\s+([A-Za-z_]\w*)(?:\s*:\s*([A-Za-z_][\w.<>,?]*))?\s*=\s*([A-Z][\w.]*)\s*(?:<[^>]*>)?\s*\("""
        )

        for (match in methodBlockRegex.findAll(controllerContent)) {
            val functionName = match.groupValues[1]
            val start = match.range.first

            val annotationsBlock = controllerContent.substring(0, start).lines()
                .asReversed()
                .takeWhile { it.trim().startsWith("@") || it.isBlank() }
                .asReversed()
                .joinToString("\n")

            val httpMethod = Regex("""@(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)""")
                .find(annotationsBlock)?.groupValues?.get(1)
                ?: continue
            val path = Regex("""@Path\("([^"]+)"\)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "Unknown"
            val consumes = Regex("""@Consumes\("([^"]+)"\)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "None"
            val produces = Regex("""@Produces\("([^"]+)"\)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "None"

            val functionStart = controllerContent.indexOf("{", match.range.last)
            val bodyContent = extractFunctionBody(controllerContent, functionStart)

            val calls = callRegex.findAll(bodyContent)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()

            val usedVarNamesInOrder = calls.map { it.first }
            val callKindByVar = calls.associate { it.first to it.second }

            val declsInFn: Map<String, String> = buildMap {
                putAll(controllerProps)
                inFunctionInitRegex.findAll(bodyContent).forEach { m ->
                    val varName = m.groupValues[1]
                    val annotatedType = m.groupValues[2]
                    val ctorType = m.groupValues[3]
                    val t = (if (annotatedType.isNotBlank()) annotatedType else ctorType)
                        .substringAfterLast('.')
                        .substringBefore('<')
                        .trim()
                    put(varName, t)
                }
            }

            fun classifyHandler(varName: String): String? {
                val simpleType = declsInFn[varName] ?: return null
                val kind = callKindByVar[varName] ?: "execute"

                val isK = khandlerIndex.containsKey(simpleType)
                if (isK) return simpleType

                val exists = fileIndex.containsKey(simpleType)
                if (!exists) return null

                val isAwsByCall = kind == "handleRequest"
                val isAwsByName = simpleType.startsWith("RequestHandler")
                if (isAwsByCall || isAwsByName) return "$simpleType (aws)"

                return "$simpleType (not linked)"
            }

            val resolvedInFn: Map<String, String> =
                usedVarNamesInOrder.distinct()
                    .mapNotNull { v -> classifyHandler(v)?.let { v to it } }
                    .toMap()

            val chosenVar = usedVarNamesInOrder.firstOrNull { it in resolvedInFn }
            val handler = chosenVar?.let { resolvedInFn[it] } ?: "Unknown"

            results.add(
                mapOf(
                    "method" to httpMethod,
                    "path" to path,
                    "consumes" to consumes,
                    "produces" to produces,
                    "function" to functionName,
                    "handler" to handler
                )
            )
        }

        return results
    }

    private fun buildSimpleFileIndex(moduleDir: File): Map<String, File> {
        val srcRoot = File(moduleDir, "src/main/kotlin")
        if (!srcRoot.exists()) return emptyMap()
        val index = mutableMapOf<String, File>()
        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val simple = f.nameWithoutExtension
                index.putIfAbsent(simple, f)
            }
        return index
    }

    private fun buildKHandlerIndex(moduleDir: File): Map<String, String> {
        val srcRoot = File(moduleDir, "src/main/kotlin")
        if (!srcRoot.exists()) return emptyMap()

        val index = mutableMapOf<String, String>()

        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()
                val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")
                    .find(content)?.groupValues?.get(1).orEmpty()

                val decls = findTypeDecls(content)
                decls.forEach { d ->
                    if (!d.isData && headerImplementsKHandler(d.header)) {
                        val fq = if (pkg.isBlank()) d.name else "$pkg.${d.name}"
                        index.putIfAbsent(d.name, fq)
                    }
                }
            }

        return index
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

    private fun extractFunctionBody(content: String, startIndex: Int): String {
        var openBraces = 0
        var index = startIndex
        var startBody = -1
        while (index < content.length) {
            val char = content[index]
            if (char == '{') {
                openBraces++
                if (startBody == -1) startBody = index
            } else if (char == '}') {
                openBraces--
                if (openBraces == 0 && startBody != -1) {
                    return content.substring(startBody, index + 1)
                }
            }
            index++
        }
        return ""
    }

    private fun extractControllerBasePath(content: String): String? {
        val regex = Regex("""@Path\("([^"]+)"\)\s*(?:@[A-Za-z_][^\n]*\s*)*class\s+""")
        return regex.find(content)?.groups?.get(1)?.value
    }

    private fun isControllerFile(content: String): Boolean {
        return Regex("""@Path\("[^"]+"\)\s*(?:@[A-Za-z_][^\n]*\s*)*class\s+""").containsMatchIn(content)
    }
}
