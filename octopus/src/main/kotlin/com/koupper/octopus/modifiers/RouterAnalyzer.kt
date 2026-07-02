package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.shared.monitoring.ExecutionMonitor
import java.io.File

class RouterAnalyzer {

    fun analyzeRouters(
        moduleDir: File,
        port: Int = 0,
        outputFileName: String = "controllers.json"
    ) {
        val srcRoot = File(moduleDir, "src/main/kotlin")
        if (!srcRoot.exists()) return

        val routerFiles = srcRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { isRouterFile(it.readText()) }
            .toList()

        if (routerFiles.isEmpty()) return

        val handlerGenerics = mutableMapOf<String, Pair<String, String>>()
        val handlerRegex = Regex("""(?s)class\s+([A-Za-z_]\w*).*?(?::|implements)\s*KHandler\s*<\s*([^,]+)\s*,\s*([^>]+)\s*>""")
        
        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                handlerRegex.findAll(text).forEach { match ->
                    val handlerName = match.groupValues[1]
                    val inputType = match.groupValues[2].trim().substringAfterLast('.')
                    val outputType = match.groupValues[3].trim().substringAfterLast('.')
                    handlerGenerics[handlerName] = Pair(inputType, outputType)
                }
            }

        val allControllersData = mutableListOf<Map<String, Any?>>()

        val routersData = routerFiles
            .map { routerFile ->
                val content = routerFile.readText()
                val endpoints = extractRouterEndpoints(content, handlerGenerics)
                mapOf(
                    "port" to port,
                    "controller" to routerFile.nameWithoutExtension,
                    "path" to "/",
                    "endpoints" to endpoints
                )
            }
            .filter { (it["endpoints"] as List<*>).isNotEmpty() }
            .toList()

        if (routersData.isEmpty()) return

        val monitor = app.getInstance(com.koupper.shared.monitoring.ExecutionMonitor::class)
        monitor.reportPayload("routers", routersData)
    }

    private fun isRouterFile(content: String): Boolean {
        val hasRouterDsl = Regex("""\bRuntimeRouterDsl\b""").containsMatchIn(content)
        val hasRegisterRouter = Regex("""\bregisterRouter\s*\{""").containsMatchIn(content)
        val hasHttpVerbs = Regex("""\b(get|post|put|delete|patch|options|head)\s*<[^>]+>\s*\{""").containsMatchIn(content)
        return hasRouterDsl || hasRegisterRouter || hasHttpVerbs
    }

    private fun extractRouterEndpoints(content: String, handlerGenerics: Map<String, Pair<String, String>>): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val stripped = stripKotlinComments(content)

        val handlerInitRegex = Regex("""(?m)\bval\s+([A-Za-z_]\w*)\s*(?::\s*[A-Za-z_]\w*)?\s*=\s*([A-Za-z_]\w*Handler(?:<[^>]+>)?)\b""")
        val handlerMap = mutableMapOf<String, String>()
        handlerInitRegex.findAll(stripped).forEach { match ->
            handlerMap[match.groupValues[1]] = match.groupValues[2].substringBefore('<')
        }

        val methodRegex = Regex("""\b(get|post|put|delete|patch|options|head)\s*<([^>]+)>\s*\{""")
        val methodMatches = methodRegex.findAll(stripped).toList()

        for (match in methodMatches) {
            val httpMethod = match.groupValues[1].uppercase()
            val consumesRaw = match.groupValues[2].trim()
            var consumes = if (consumesRaw == "Any") "None" else consumesRaw.substringAfterLast('.')
            var produces = "None"

            val startIndex = match.range.last
            val bodyContent = extractBlockBody(stripped, startIndex)

            val pathRegex = Regex("""\bpath\s*\{\s*"([^"]+)"\s*\}""")
            val pathMatch = pathRegex.find(bodyContent)
            val path = pathMatch?.groupValues?.get(1) ?: "Unknown"

            val executeRegex = Regex("""\b([A-Za-z_]\w*)\s*\.\s*(?:execute|handleRequest|handle)\b""")
            val executeMatches = executeRegex.findAll(bodyContent).map { it.groupValues[1] }.toList()
            
            var handlerName = "UnknownHandler"
            for (v in executeMatches) {
                if (handlerMap.containsKey(v)) {
                    handlerName = handlerMap[v]!!
                    break
                }
            }

            if (handlerGenerics.containsKey(handlerName)) {
                val (hInput, hOutput) = handlerGenerics[handlerName]!!
                if (hInput == "Unit" || hInput == "Any") {
                    if (consumes == "None") consumes = "None"
                } else {
                    consumes = hInput
                }
                if (hOutput == "Unit" || hOutput == "Any") {
                    produces = "None"
                } else {
                    produces = hOutput
                }
            }

            val functionRegex = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
            val functionMatch = functionRegex.find(bodyContent)
            val functionName = functionMatch?.groupValues?.get(1) ?: "anonymous"

            results.add(
                mapOf(
                    "method" to httpMethod,
                    "path" to path,
                    "consumes" to consumes,
                    "produces" to produces,
                    "handler" to handlerName,
                    "function" to functionName
                )
            )
        }

        return results
    }

    private fun stripKotlinComments(src: String): String {
        var s = src.replace(Regex("""(?s)/\*.*?\*/"""), "")
        s = s.replace(Regex("""(?m)//.*$"""), "")
        return s
    }

    private fun extractBlockBody(content: String, startIndex: Int): String {
        var openBraces = 1
        var index = startIndex + 1
        var startBody = index
        while (index < content.length) {
            val char = content[index]
            if (char == '{') {
                openBraces++
            } else if (char == '}') {
                openBraces--
                if (openBraces == 0) {
                    return content.substring(startBody, index)
                }
            }
            index++
        }
        return ""
    }
}
