package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.interfaces.Container
import com.koupper.octopus.isContainerType
import com.koupper.octopus.isRelativeScriptFile
import com.koupper.octopus.routes.GradleBuilder
import com.koupper.octopus.routes.RouteDefinition
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.files.downloadFile
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.script.ScriptEngineManager
import kotlin.system.exitProcess
import kotlin.io.path.createTempFile

val getRealScriptNameFrom: (String) -> String = { script ->
    val scriptId = if (script.endsWith(".kts")) script else "${script}.kts"

    val finalScriptName = (if (!isRelativeScriptFile(scriptId)) {
        scriptId.substring(script.lastIndexOf("/") + 1)
    } else {
        scriptId
    })

    finalScriptName
}

class ModuleMaker(private val container: Container) : Process {
    override lateinit var name: String
    override var metadata: MutableMap<String, Any> = mutableMapOf()
    override lateinit var moduleType: String
    override var location: String = "UNDEFINED"
    private val fileHandler = this.container.createInstanceOf(FileHandler::class)
    private val textFileHandler = this.container.createInstanceOf(TextFileHandler::class)
    private val registeredScripts = mutableListOf<String?>()

    override fun register(name: String, metadata: Map<String, Any>): Process {
        this.name = name
        this.metadata.putAll(metadata)
        this.moduleType = this.metadata["moduleType"] as String

        return this
    }

    override fun processName(): String {
        return this.name
    }

    override fun processType(): String {
        return this.moduleType
    }

    override fun run() {
        if (File(this.name).exists()) {
            if (!this.moduleType.equals("DEPLOYABLE_SCRIPT", true)) {
                println("\n$ANSI_YELLOW_229 A module named '$name' already exist. $ANSI_RESET \n")

                exitProcess(0)
            }

            this.locateScriptsForDeployable(this.metadata["scriptsToExecute"] as List<String>, this.name)

            return
        }

        this.buildByType()
    }

    private fun buildByType() {
        val self = this

        when {
            this.moduleType.equals("FRONT", true) -> {
                val modelProject = this.fileHandler.unzipFile(env("MODEL_FRONT_PROJECT_URL"))

                File("${modelProject.name}.zip").delete()

                this.textFileHandler.replaceLine(
                    2,
                    "  \"name\": \"${this.name}\",",
                    "${modelProject.absolutePath}/package.json",
                    true
                )

                this.textFileHandler.replaceLine(
                    3,
                    "  \"version\": \"${this.metadata["moduleVersion"]}\",",
                    "${modelProject.absolutePath}/package.json",
                    true
                )

                Files.move(Paths.get(modelProject.name), Paths.get(this.name))
            }
            this.moduleType.equals("BACK", true) -> {

            }
            this.moduleType.equals("CONTROLLER", true) -> {
                val location = this.metadata["projectLocation"] as String

                this.location = location

                GradleBuilder.build(this.name, this.location, this.container) {
                    this.version = self.metadata["moduleVersion"] as String
                }.build()

                this.addLibs(location)

                this.locateScriptsForController(this.metadata["methods"] as List<RouteDefinition>, location)

                Files.delete(Paths.get("$location/src/main/kotlin/scripts/script.kt"))

                Files.move(Paths.get(location), Paths.get(this.name))
            }
            this.moduleType.equals("DEPLOYABLE_SCRIPT", true) -> {
                val modelProject = this.fileHandler.unzipFile(env("MODEL_BACK_PROJECT_URL"))

                File("${modelProject.name}.zip").delete()

                this.location = modelProject.path

                GradleBuilder.build(this.name, modelProject.path, this.container) {
                    this.version = self.metadata["moduleVersion"] as String
                }.build()

                this.addLibs(modelProject.absolutePath)

                this.locateScriptsForDeployable(
                    this.metadata["scriptsToExecute"] as List<String>,
                    modelProject.absolutePath
                )

                Files.move(Paths.get(modelProject.name), Paths.get(this.name))
            }
        }
    }

    private fun addLibs(projectName: String) {
        print("\u001B[38;5;155m\nRequesting an optimized process manager... \u001B[0m")

        File("$projectName/libs").mkdir()

        downloadFile(
            URL(env("OPTIMIZED_PROCESS_MANAGER_URL")),
            "$projectName/libs/octopus-${env("OCTOPUS_VERSION")}.jar"
        )

        println("\u001B[38;5;155m✔\u001B[0m")

        println("\u001B[38;5;155mOptimized process manager located successfully.\u001B[0m")
    }

    private fun locateScriptsForController(routes: List<RouteDefinition>, targetPath: String) {
        if (routes.isEmpty()) {
            println("\u001B[38;5;229mNo routes configured...\u001B[0m")

            return
        }

        routes.forEach { route ->
            val script = getRealScriptNameFrom(route.script())

            val scriptFile = this.findScript(script)

            val callableReturnType = this.textFileHandler.getContentBetweenContent("->", "=", filePath = scriptFile.path)[0].trim()

            if (route.response()?.simpleName != callableReturnType) {
                if ()

                this.rollback()
                throw Exception("[$callableReturnType] return type in script [$script] does not match controller return type [${route.response()?.simpleName}].")
            }

            if (script.isEmpty()) {
                throw Exception("${route.controllerName()} should have an assigned script file.")
            }

            locateScript(script, targetPath)
        }

        println("\u001B[38;5;155mScripts located.\u001B[0m")
    }

    private fun locateScriptsForDeployable(scripts: List<String>, targetPath: String) {
        if (scripts.isEmpty()) {
            println("\u001B[38;5;229mNo scripts configured...\u001B[0m")

            return
        }

        scripts.forEach { script ->
            if (script.isEmpty()) {
                throw Exception("A script should be specified.")
            }

            locateScript(script, targetPath)
        }

        println("\u001B[38;5;155mScripts located.\u001B[0m")
    }

    private fun locateScript(script: String, targetPath: String) {
        val finalScriptName = getRealScriptNameFrom(script)

        if (finalScriptName.isNotEmpty()) {
            if (Files.notExists(Paths.get("$targetPath/src/main/kotlin/scripts/${finalScriptName.replace(".kts", ".kt")}"))) {
                val finalScriptPath = "$targetPath/src/main/kotlin/scripts/${finalScriptName.replace(".kts", ".kt")}"

                this.commitScript(finalScriptName, finalScriptPath)

                this.changeCallbackVariable(finalScriptName, finalScriptPath)

                print("$ANSI_GREEN_155$script$ANSI_RESET")

                println("\u001B[38;5;155m...[ok]\u001B[0m")
            }
        }
    }

    private fun commitScript(scriptPath: String, targetPath: String) {
        val scriptFile = this.validateScript(scriptPath)

        val tmpPath = createTempFile("k-s-", ".kt")

        val tmpFile = File(tmpPath.toAbsolutePath().toString())

        tmpFile.printWriter().use { writer ->
            var lineNumber = 0

            scriptFile.forEachLine { line ->
                if (lineNumber == 0) {
                    writer.println("package scripts\n\n$line")
                } else {
                    writer.println(line)
                }

                lineNumber++
            }
        }

        tmpFile.renameTo(File(targetPath))
    }

    private fun validateScript(scriptPath: String): File {
        val scriptFile = this.findScript(scriptPath)

        val sentence = scriptFile.readText(Charsets.UTF_8)

        if (sentence.isNotEmpty()) {
            with(ScriptEngineManager().getEngineByExtension("kts")) {
                eval(sentence)
            }
        }

        return scriptFile
    }

    private fun findScript(scriptPath: String): File {
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
                    this.rollback()

                    throw Exception("The script file does not exist.")
                }
            }
        }

        return scriptFile
    }

    private fun changeCallbackVariable(scriptName: String, scriptPath: String) {
        if (this.registeredScripts.contains(scriptName)) {
            println("\u001B[33m$scriptName script exist. Ignoring relocation.\u001B[0m")
            return
        }
        this.registeredScripts.add(scriptName)

        val finalValName: String

        val finalNameWhitHyphen = this.getCallbackFinalName(scriptName, "-")

        val finalNameWhitDash = this.getCallbackFinalName(scriptName, "_")

        finalValName = if (finalNameWhitHyphen.isNotEmpty()) {
            finalNameWhitHyphen
        } else if (finalNameWhitDash.isNotEmpty()) {
            finalNameWhitDash
        } else {
            throw Exception("Script file can not be empty.")
        }

        val callableName = this.textFileHandler.getContentBetweenContent("val", ":", filePath = scriptPath)

        val callableLine = this.textFileHandler.getNumberLineFor("val${callableName[0]}: (", scriptPath)

        val callableLineContent = this.textFileHandler.getContentForLine(callableLine, scriptPath)

        val renamedCallable = callableLineContent.replace(callableName[0], " $finalValName")

        this.textFileHandler.replaceLine(callableLine, renamedCallable, scriptPath, true)
    }

    private fun getCallbackFinalName(scriptName: String, delimiter: String): String {
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

    private fun rollback() {
        if (this.location != "UNDEFINED") {
            val file = File(location)

            if (file.exists()) {
                file.deleteRecursively()
            }
        }
    }
}
