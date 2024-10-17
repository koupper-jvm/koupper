package com.koupper.octopus.modules;

import com.koupper.configurations.utilities.ANSIColors
import com.koupper.container.app
import com.koupper.octopus.process.getRealScriptNameFrom
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.TextFileHandler
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.script.ScriptEngineManager

const val EXTENSIONS_FOLDER_NAME = "extensions"

abstract class Module {
    private val registeredScripts = mutableListOf<String?>()
    private val textFileHandler = app.createInstanceOf(TextFileHandler::class)
    private val fileHandler = app.createInstanceOf(FileHandler::class)
    private var targetPath: String = "UNDEFINED"

    companion object {
        fun locateScripts(scripts: List<String>, targetPath: String, packageName: String) = locateScriptsInPackage(scripts, targetPath, packageName)

        private fun locateScriptsInPackage(scripts: List<String>, targetPath: String, packageName: String) {
            locateScriptsInPackage(scripts, targetPath, packageName)
        }
    }

    abstract fun build()

    fun locateScriptInPackage(script: String, targetPath: String, packageName: String) {
        val finalScriptName = getRealScriptNameFrom(script)

        this.targetPath = targetPath

        if (finalScriptName.isNotEmpty()) {
            val finalScriptPath = "$targetPath/src/main/kotlin/${packageName.replace(".", "/")}/$EXTENSIONS_FOLDER_NAME/${finalScriptName.replace(".kts", ".kt")}"

            if (Files.notExists(Paths.get(finalScriptPath))) {
                commitScriptInPackage(finalScriptName, finalScriptPath, packageName)

                changeFunctionName(finalScriptName, finalScriptPath)

                print("${ANSIColors.ANSI_GREEN_155}$script${ANSIColors.ANSI_RESET}")

                println("\u001B[38;5;155m...[ok]\u001B[0m")
            }
        }
    }

    private fun commitScriptInPackage(scriptPath: String, targetPath: String, packageName: String) {
        val scriptFile = validateScript(scriptPath)

        val tmpPath = Files.createTempFile("k-s-", ".kt").toFile()

        val tmpFile = File(tmpPath.absolutePath)

        tmpFile.printWriter().use { writer ->
            var lineNumber = 0

            scriptFile.forEachLine { line ->
                if (lineNumber == 0) {
                    writer.println("package ${packageName.replace("/", ".")}.$EXTENSIONS_FOLDER_NAME\n\n$line")
                } else {
                    writer.println(line)
                }

                lineNumber++
            }
        }

        tmpFile.renameTo(File(targetPath))
    }

    private fun changeFunctionName(scriptName: String, scriptPath: String) {
        if (this.registeredScripts.contains(scriptName)) {
            println("\u001B[33m$scriptName script exist. Ignoring relocation.\u001B[0m")
            return
        }
        this.registeredScripts.add(scriptName)

        val finalValName: String

        val finalNameWhitHyphen = this.getFunctionFinalName(scriptName, "-")

        val finalNameWhitDash = this.getFunctionFinalName(scriptName, "_")

        finalValName = if (finalNameWhitHyphen.isNotEmpty()) {
            finalNameWhitHyphen.substringBeforeLast(".")
        } else if (finalNameWhitDash.isNotEmpty()) {
            finalNameWhitDash.substringBeforeLast(".")
        } else {
            throw Exception("Script file can not be empty.")
        }

        val callableName = this.textFileHandler.getContentBetweenContent("val", ":", filePath = scriptPath)

        val callableLine = this.textFileHandler.getNumberLineFor("val${callableName[0]}: (", scriptPath)

        val callableLineContent = this.textFileHandler.getContentForLine(callableLine, scriptPath)

        val renamedCallable = callableLineContent.replace(callableName[0], " $finalValName")

        this.textFileHandler.replaceLine(callableLine, renamedCallable, scriptPath, true)
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
                throw Exception("The script file does not exist in the current path.")
            } else {
                scriptFile = fileInCurrentPath
            }
        } catch (e: Exception) {
            try {
                val fileInLibsPath = this.fileHandler.load("libs/$scriptPath")

                if (!fileInLibsPath.exists()) {
                    throw Exception("The script file does not exist in the libs folder.")
                } else {
                    scriptFile = fileInLibsPath
                }
            } catch (e: Exception) {
                try {
                    val fileInEnvVariablePath = this.fileHandler.load(env("KOUPPER_PATH"))

                    if (!fileInEnvVariablePath.exists()) {
                        throw Exception("The script file does not exist in KOUPPER_PATH location.")
                    } else {
                        scriptFile = fileInEnvVariablePath
                    }
                } catch (e: Exception) {
                    if (this.targetPath != "UNDEFINED") {
                        val file = File(this.targetPath)

                        if (file.exists()) {
                            file.deleteRecursively()
                        }
                    }

                    throw Exception("The script file does not exist.")
                }
            }
        }

        return scriptFile
    }
}

fun Module.locateScriptsInPackage(scripts: List<String>, targetPath: String, packageName: String) {
    if (scripts.isEmpty()) {
        println("\u001B[38;5;229mNo scripts configured...\u001B[0m")

        return
    }

    scripts.forEach { script ->
        if (script.isEmpty()) {
            throw Exception("A script should be specified.")
        }

        locateScriptInPackage(script, targetPath, packageName)
    }

    println("\u001B[38;5;155mScripts located.\u001B[0m")
}
