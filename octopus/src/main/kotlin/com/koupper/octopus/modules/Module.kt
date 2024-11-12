package com.koupper.octopus.modules;

import com.koupper.configurations.utilities.ANSIColors
import com.koupper.container.app
import com.koupper.octopus.process.getRealScriptNameFrom
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.script.ScriptEngineManager

const val EXTENSIONS_FOLDER_NAME = "extensions"

fun isPrimitive(typeName: String): Boolean {
    val kotlinPrimitives = setOf("Int", "Double", "Float", "Long", "Short", "Byte", "Boolean", "Char", "String")
    return typeName in kotlinPrimitives
}

abstract class Module {
    protected val registeredScriptPackages: MutableMap<String, String> = mutableMapOf()
    protected val registeredScripts: MutableMap<String, Pair<List<String>, String>> = mutableMapOf()
    protected val createdScripts: MutableMap<String, String> = mutableMapOf()
    private val fileHandler = app.createInstanceOf(FileHandler::class)
    private lateinit var scriptLocationPath: String
    private lateinit var validatedScriptFile: File

    abstract fun build()

    companion object {
        fun locateScripts(scripts: Map<String, String>, targetPath: String, packageName: String) =
            locateScriptsInPackage(scripts, targetPath, packageName)

        private fun locateScriptsInPackage(scripts: Map<String, String>, targetPath: String, packageName: String) {
            locateScriptsInPackage(scripts, targetPath, packageName)
        }
    }

    fun locateScriptInPackage(handler: Pair<String, String>, targetPath: String, packageName: String) {
        val lastSlash = handler.second.lastIndexOf('/')

        val lastBackslash = handler.second.lastIndexOf('\\')

        val lastIndexOfPath = maxOf(lastSlash, lastBackslash)

        this.scriptLocationPath = handler.second.substring(0, lastIndexOfPath + 1)

        val scriptName = this.buildScriptName(handler.second)

        val finalScriptPath = "$targetPath/src/main/kotlin/${
            packageName.replace(
                ".",
                "/"
            )
        }/$EXTENSIONS_FOLDER_NAME/${this.scriptLocationPath}$scriptName.kt"

        this.validatedScriptFile = validateScript(handler.second)

        val functionSignatureRegex = Regex(""":\s*\((.*)\)\s*->\s*([^\s=]+)""")

        this.validatedScriptFile.forEachLine { line ->
            val matchResult = functionSignatureRegex.find(line)

            if (matchResult != null) {
                val rawParams = matchResult.groupValues[1]
                val returnType = matchResult.groupValues[2]

                val inputTypes = mutableListOf<String>()
                var currentParam = StringBuilder()
                var depth = 0

                for (char in rawParams) {
                    when {
                        char == ',' && depth == 0 -> {
                            inputTypes.add(currentParam.toString().trim())
                            currentParam = StringBuilder()
                        }

                        char == '<' -> {
                            depth++
                            currentParam.append(char)
                        }

                        char == '>' -> {
                            depth--
                            currentParam.append(char)
                        }

                        else -> {
                            currentParam.append(char)
                        }
                    }
                }

                if (currentParam.isNotBlank()) {
                    inputTypes.add(currentParam.toString().trim())
                }

                this.registeredScripts[handler.first] = Pair(inputTypes, returnType)

                this.registeredScriptPackages[handler.first] = this.scriptLocationPath.replace(Regex("""(?<!\\)[/\\](?!\\)"""), ".")
            }
        }

        if (Files.notExists(Paths.get(finalScriptPath))) {
            commitScriptInPackage(handler, finalScriptPath, packageName)

            print("${handler.second}...")

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
        } else {
            throw Exception("Script file can not be empty.")
        }
    }

    private fun commitScriptInPackage(handler: Pair<String, String>, targetPath: String, packageName: String) {
        if (this.createdScripts[handler.first] != null) {
            println("\u001B[33m${this.buildScriptName(handler.second)} script exist. Ignoring relocation.\u001B[0m")
            return
        }

        val tmpFile = Files.createTempFile("k-s-", ".kt").toFile()

        val callbackFunctionSignature = Regex(
            """^val\s+\w+\s*:\s*\(?(?:[\w<>,\s]*)?\)?\s*->\s*[\w<>]*\s*=\s*\{.*"""
        )

        tmpFile.printWriter().use { writer ->
            var lineNumber = 0

            this.validatedScriptFile.forEachLine { line ->
                if (lineNumber == 0) {
                    var scriptPackage = this.scriptLocationPath.replace(Regex("""(?<!\\)[/\\](?!\\)"""), ".")
                    scriptPackage = scriptPackage.substring(0, scriptPackage.lastIndexOf("."))

                    writer.println("package $packageName.$EXTENSIONS_FOLDER_NAME.${scriptPackage}\n\n$line")
                } else if (callbackFunctionSignature.matches(line)) {
                    val regex = Regex("""^val\s+(\w+)\s*(:\s*\(?[\w<>,\s]*\)?\s*->\s*[\w<>]*\s*=\s*\{.*)""")

                    val newLine = line.replace(regex) {
                        "val ${buildScriptName(handler.second)}${it.groupValues[2]}"
                    }

                    this.createdScripts[handler.first] = targetPath

                    writer.println(newLine)
                } else {
                    writer.println(line)
                }
                lineNumber++
            }
        }

        val targetFile = File(targetPath)
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

    private fun validateScript(scriptPath: String): File {
        val scriptFile = searchFileInLocations(scriptPath)

        val sentence = scriptFile.readText(Charsets.UTF_8)

        if (sentence.isNotEmpty()) {
            with(ScriptEngineManager().getEngineByExtension("kts")) {
                eval(sentence)
            }
        }

        return scriptFile
    }

    private fun searchFileInLocations(scriptPath: String): File {
        var scriptFile: File

        try {
            val fileInCurrentPath = this.fileHandler.load(scriptPath)

            if (!fileInCurrentPath.exists()) {
                throw Exception("The script file $scriptPath does not exist in the current path.")
            } else {
                scriptFile = fileInCurrentPath
            }
        } catch (e: Exception) {
            try {
                val fileInLibsPath = this.fileHandler.load("libs/$scriptPath")

                if (!fileInLibsPath.exists()) {
                    throw Exception("The script file $scriptPath does not exist in the libs folder.")
                } else {
                    scriptFile = fileInLibsPath
                }
            } catch (e: Exception) {
                try {
                    val fileInEnvVariablePath = this.fileHandler.load(env("KOUPPER_PATH"))

                    if (!fileInEnvVariablePath.exists()) {
                        throw Exception("The script file $scriptPath does not exist in KOUPPER_PATH location.")
                    } else {
                        scriptFile = fileInEnvVariablePath
                    }
                } catch (e: Exception) {
                    throw Exception("The script file $scriptPath does not exist.")
                }
            }
        }

        return scriptFile
    }
}

fun Module.locateScriptsInPackage(scripts: Map<String, String>, targetPath: String, packageName: String) {
    if (scripts.isEmpty()) {
        println("\u001B[38;5;229mNo scripts configured...\u001B[0m")

        return
    }

    for ((key, value) in scripts) {
        if (key.isEmpty() || value.isEmpty()) {
            throw Exception("A script should be specified.")
        }

        val singleScriptMap = Pair(key, value)

        locateScriptInPackage(singleScriptMap, targetPath, packageName)
    }

    println("${ANSIColors.ANSI_YELLOW_229}\nThe scripts were located.${ANSIColors.ANSI_RESET}")
}
