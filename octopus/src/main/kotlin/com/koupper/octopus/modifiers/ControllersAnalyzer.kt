package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.octopus.createDefaultConfiguration
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.JSONFileHandlerImpl
import java.io.File

class ControllersAnalyzer {
    fun analyzeControllers(
        moduleDir: File,
        port: Int = 0,
        outputFileName: String = "controllers.json"
    ) {
        val controllersDirs = File(moduleDir, "src/main/kotlin")
            .walk()
            .filter { it.isDirectory && it.name == "controllers" }
            .toList()

        if (controllersDirs.isEmpty()) return

        val allControllersData = mutableListOf<Map<String, Any?>>()

        for (controllersDir in controllersDirs) {
            val controllersData = controllersDir.listFiles { file ->
                file.extension == "kt"
            }?.map { controllerFile ->
                val controllerContent = controllerFile.readText()
                val endpoints = extractControllerInfo(controllerContent)
                mapOf(
                    "port" to port,
                    "controller" to controllerFile.nameWithoutExtension,
                    "path" to (extractControllerBasePath(controllerContent) ?: "/"),
                    "endpoints" to endpoints
                )
            } ?: emptyList()

            allControllersData.addAll(controllersData)
        }

        val outputFile = File(
            System.getProperty("user.home"),
            ".koupper/helpers/$outputFileName"
        )
        outputFile.parentFile.mkdirs()

        val jsonFileHandler = app.getInstance(JSONFileHandler::class) as JSONFileHandlerImpl<List<Map<String, Any?>>>

        outputFile.writeText(jsonFileHandler.toJsonString(allControllersData))
    }

    private fun extractControllerInfo(controllerContent: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()

        val importHandlerNames: Set<String> =
            Regex("""^import\s+([\w.]+)\.(\w+)\s*$""", RegexOption.MULTILINE)
                .findAll(controllerContent)
                .map { it.groupValues[1] to it.groupValues[2] }
                .filter { (pkg, _) -> pkg.contains(".handlers") }
                .map { it.second }
                .toSet()

        val methodBlockRegex = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
        val handlerInitRegex = Regex(
            """\bval\s+([A-Za-z_]\w*)(?:\s*:\s*([A-Za-z_][\w.<>,?]*))?\s*=\s*([A-Za-z_][\w.]*)\s*(?:<[^>]*>)?\s*\([^)]*\)"""
        )
        val handlerCallRegex = Regex("""\b(\w+)\s*\.\s*(?:handleRequest|handle)\s*\(""")

        for (match in methodBlockRegex.findAll(controllerContent)) {
            val functionName = match.groupValues[1]
            val start = match.range.first

            val annotationsBlock = controllerContent.substring(0, start).lines()
                .asReversed()
                .takeWhile { it.trim().startsWith("@") || it.isBlank() }
                .asReversed()
                .joinToString("\n")

            val httpMethod = Regex("""@(GET|POST|PUT|DELETE)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "Unknown"
            val path = Regex("""@Path\("([^"]+)"\)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "Unknown"
            val consumes = Regex("""@Consumes\("([^"]+)"\)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "None"
            val produces = Regex("""@Produces\("([^"]+)"\)""")
                .find(annotationsBlock)?.groupValues?.get(1) ?: "None"

            val functionStart = controllerContent.indexOf("{", match.range.last)
            val bodyContent = extractFunctionBody(controllerContent, functionStart)

            val usedVarNamesInOrder = handlerCallRegex.findAll(bodyContent).map { it.groupValues[1] }.toList()

            val declsInFn: Map<String, String> =
                handlerInitRegex.findAll(bodyContent).associate { m ->
                    val varName = m.groupValues[1]
                    val annotatedType = m.groupValues[2]
                    val ctorType = m.groupValues[3]
                    val t = if (annotatedType.isNotBlank()) annotatedType else ctorType
                    val simple = t.substringAfterLast('.').substringBefore('<')
                    varName to simple
                }

            val handlersInFn: Map<String, String> =
                usedVarNamesInOrder.distinct()
                    .mapNotNull { name ->
                        val simple = declsInFn[name] ?: return@mapNotNull null
                        if (simple.endsWith("Handler") || simple in importHandlerNames) name to simple else null
                    }.toMap()

            val chosenHandlerName = usedVarNamesInOrder.firstOrNull { it in handlersInFn }
            val handler = chosenHandlerName?.let { handlersInFn[it] } ?: "Unknown"

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
        val regex = Regex("""@Path\("([^"]+)"\)""")
        return regex.find(content)?.groups?.get(1)?.value
    }
}
