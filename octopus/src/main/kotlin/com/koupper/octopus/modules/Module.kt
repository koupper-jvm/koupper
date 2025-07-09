package com.koupper.octopus.modules;

import com.koupper.configurations.utilities.ANSIColors
import com.koupper.container.app
import com.koupper.octopus.Octopus
import com.koupper.octopus.Octopus.Companion.extractExportFunctionName
import com.koupper.octopus.Octopus.Companion.extractExportFunctionSignature
import com.koupper.octopus.process.getRealScriptNameFrom
import com.koupper.providers.files.FileHandler
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.script.ScriptEngineManager

const val EXTENSIONS_FOLDER_NAME = "extensions"

fun isPrimitive(typeName: String): Boolean {
    val kotlinPrimitives = setOf("Int", "Double", "Float", "Long", "Short", "Byte", "Boolean", "Char", "String")
    return typeName in kotlinPrimitives
}

fun validateScript(scriptPath: String): Result<File> {
    return try {
        val scriptFile = File(scriptPath)
        val sentence = scriptFile.readText(Charsets.UTF_8)

        if (sentence.isNotEmpty()) {
            val engine = ScriptEngineManager().getEngineByExtension("kts")
                ?: throw IllegalStateException("No Kotlin Script Engine found for '.kts' extension.")

            val exportedFunctionName = extractExportFunctionName(sentence)

            if (exportedFunctionName != null) {
                engine.eval(sentence)
            } else {
                println("No function annotated with @Export was found.")
            }
        }

        Result.success(scriptFile)
    } catch (e: Exception) {
        Result.failure(e)
    }
}


abstract class Module {
    protected val registeredScriptPackages: MutableMap<String, String> = mutableMapOf()
    protected val createdScripts: MutableMap<String, String> = mutableMapOf()
    protected val registeredScripts: MutableMap<String, Pair<List<String>, String>> = mutableMapOf()
    protected val registeredFunctionNames: MutableMap<String, String?> = mutableMapOf()
    private lateinit var scriptLocationPath: String
    private lateinit var validatedScriptFile: File

    abstract fun build()

    companion object {
        fun locateScripts(context: String, scripts: Map<String, String>, targetPath: String, packageName: String) =
            locateScriptsInPackage(context, scripts, targetPath, packageName)

        private fun locateScriptsInPackage(context: String, scripts: Map<String, String>, targetPath: String, packageName: String) {
            locateScriptsInPackage(context, scripts, targetPath, packageName)
        }
    }

    private fun extractFunctionSignature(script: String): Pair<List<String>, String>? {
        val scriptContent = File(script).readText()

        return extractExportFunctionSignature(scriptContent)
    }

    private fun getFunctionName(): String? {
        val scriptContent = this.validatedScriptFile.readText()

        return extractExportFunctionName(scriptContent)
    }

    private fun createValidatedScriptFile(context: String, scriptPath: String) {
        val executableScriptFinalPath =
            if (scriptPath.startsWith(File.separator)) scriptPath.substringAfter(File.separator) else scriptPath

        this.validatedScriptFile = validateScript(context + File.separator + executableScriptFinalPath).getOrThrow()
    }

    fun processScript(context: String, handlerToScript: Pair<String, String>, targetPath: String, packageName: String) {
        val lastSlash = handlerToScript.second.lastIndexOf('/')

        val lastBackslash = handlerToScript.second.lastIndexOf('\\')

        val lastIndexOfPath = maxOf(lastSlash, lastBackslash)

        this.scriptLocationPath = handlerToScript.second.substring(0, lastIndexOfPath + 1)

        val scriptName = buildScriptName(handlerToScript.second)

        val finalScriptPath = "$targetPath/src/main/kotlin/${
            packageName.replace(
                ".",
                "/"
            )
        }/$EXTENSIONS_FOLDER_NAME/${this.scriptLocationPath}$scriptName.kt"

        createValidatedScriptFile(context, handlerToScript.second)

        val functionSignature = this.extractFunctionSignature(this.validatedScriptFile.path)

        this.registeredScripts[handlerToScript.first] = functionSignature as Pair<List<String>, String>

        this.registeredFunctionNames[handlerToScript.first] = this.getFunctionName()

        this.registeredScriptPackages[handlerToScript.first] =
            this.scriptLocationPath.replace(Regex("""(?<!\\)[/\\](?!\\)"""), ".")

        if (Files.notExists(Paths.get(finalScriptPath))) {
            commitScriptInPackage(handlerToScript, finalScriptPath, packageName.replace("""(?<!\\)[/\\](?!\\)""", "."))

            print("${handlerToScript.second}...")

            println("${ANSIColors.ANSI_GREEN_155}[\u2713]${ANSIColors.ANSI_RESET}")
        }
    }

    protected fun buildScriptName(scriptPath: String): String {
        val finalNameWhitHyphen = this.getFunctionFinalName(getRealScriptNameFrom(scriptPath), "-")

        val finalNameWhitDash = this.getFunctionFinalName(getRealScriptNameFrom(scriptPath), "_")

        return if (finalNameWhitHyphen.isNotEmpty()) {
            finalNameWhitHyphen.substringBeforeLast(".")
        } else if (finalNameWhitDash.isNotEmpty()) {
            finalNameWhitDash.substringBeforeLast(".")
        } else getRealScriptNameFrom(scriptPath).substringBeforeLast(".")
    }

    private fun commitScriptInPackage(handler: Pair<String, String>, finalScriptPath: String, packageName: String) {
        if (this.createdScripts[handler.first] != null) {
            println("\u001B[33m${this.buildScriptName(handler.second)} script exist. Ignoring relocation.\u001B[0m")
            return
        }

        val tmpFile = Files.createTempFile("k-s-", ".kt").toFile()

        val regex = Regex("""@Export\s+val\s+(\w+)\s*(:\s*\([^)]*\)\s*->\s*[\w<>,()\s]*\s*=\s*\{.*)""")

        tmpFile.printWriter().use { writer ->
            var lineNumber = 0

            this.validatedScriptFile.forEachLine { line ->
                if (lineNumber == 0) {
                    var scriptPackage = this.scriptLocationPath.replace(Regex("""(?<!\\)[/\\](?!\\)"""), ".")

                    val packageStartIndex = scriptPackage.lastIndexOf(".")

                    scriptPackage = if (packageStartIndex > 0) {
                        ".${scriptPackage.substring(0, packageStartIndex)}"
                    } else {
                        ""
                    }

                    writer.println("package $packageName.$EXTENSIONS_FOLDER_NAME${scriptPackage}\n\n$line")
                } else if (regex.matches(line)) {
                    val newLine = line.replace(regex) {
                        "val ${buildScriptName(handler.second)}${it.groupValues[2]}"
                    }

                    this.createdScripts[handler.first] = finalScriptPath

                    writer.println(newLine)
                } else {
                    writer.println(line)
                }
                lineNumber++
            }
        }

        val targetFile = File(finalScriptPath)
        targetFile.parentFile.mkdirs()
        tmpFile.renameTo(targetFile)
    }

    private fun getFunctionFinalName(scriptName: String, delimiter: String): String {
        val parts = scriptName.split(delimiter)

        var finalValName = ""

        if (parts.size > 1) {
            parts.forEachIndexed lit@{ index, value ->
                if (index != 0) {
                    finalValName += value.substring(0, 1).uppercase(Locale.getDefault()).plus(value.substring(1))

                    return@lit
                }

                finalValName += value
            }
        }

        return finalValName
    }
}

fun Module.locateScriptsInPackage(context: String, scripts: Map<String, String>, targetPath: String, packageName: String) {
    if (scripts.isEmpty()) {
        println("\u001B[38;5;229mNo scripts configured...\u001B[0m")

        return
    }

    for ((key, value) in scripts) {
        if (key.isEmpty()) {
            throw Exception("A handler should be specified.")
        }

        if (value.isEmpty()) {
            throw Exception("A script should be specified.")
        }

        val handlerToScript = Pair(key, value)

        processScript(context, handlerToScript, targetPath, packageName)
    }

    println("${ANSIColors.ANSI_YELLOW_229}\nThe scripts were located.${ANSIColors.ANSI_RESET}")
}
