package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_GREEN_155
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.octopus.isRelativeScriptFile
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.files.downloadFile
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempFile

class SetupModule(private val container: Container) : Process {
    override lateinit var name: String
    override var metadata: MutableMap<String, Any> = mutableMapOf()
    override lateinit var moduletype: String
    private val fileHandler = this.container.createInstanceOf(FileHandler::class)
    private val textFileHandler = this.container.createInstanceOf(TextFileHandler::class)

    override fun register(name: String, metadata: Map<String, Any>): Process {
        this.name = name
        this.metadata.putAll(metadata)
        this.moduletype = this.metadata["moduleType"] as String

        return this
    }

    override fun processName(): String {
        return this.name
    }

    override fun processType(): String {
        return this.moduletype
    }

    override fun run() {
        if (File(name).exists()) {
            if (!this.moduletype.equals("DEPLOYABLE", true)) {
                println("\n$ANSI_YELLOW_229 A module named '$name' already exist. $ANSI_RESET \n")

                exitProcess(0)
            }

            this.locateScriptsInProject(this.metadata["scriptsToExecute"] as List<String>, this.name)

            return
        }

        this.buildByType()
    }

    private fun buildByType() {
        when {
            this.moduletype.equals("FRONT", true) -> {
                val modelProject = fileHandler.unzipFile(app.env("MODEL_FRONT_PROJECT_URL"))

                File("${modelProject.name}.zip").delete()

                this.textFileHandler.replaceLine(
                    2,
                    "\"name\": \"${modelProject.name}\"",
                    "${modelProject.absolutePath}/package.json",
                    true
                )

                this.textFileHandler.replaceLine(
                    3,
                    "\"version\": \"${this.metadata["version"]}\"",
                    "${modelProject.absolutePath}/package.json",
                    true
                )

                Files.move(Paths.get(modelProject.name), Paths.get(this.name))
            }
            this.moduletype.equals("BACK", true) -> {
                /*fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/back-module.zip", this.name)

                File(this.name).delete()

                val fileHandler = this.container.createInstanceOf(TextFileHandler::class)
                fileHandler.replaceLine(10, "rootProject.name = '${this.name}'", "${Paths.get("").toAbsolutePath()}/${this.name}/settings.gradle")*/

                //val processManager = textFileHandler.read("resource://.env").getProperty("MODEL_BACK_PROJECT_URL")
            }
            this.moduletype.equals("DEPLOYABLE", true) -> {
                val modelProject = fileHandler.unzipFile(app.env("MODEL_BACK_PROJECT_URL"))

                File("${modelProject.name}.zip").delete()

                this.textFileHandler.replaceLine(
                    50,
                    "version = '${this.metadata["version"]}'",
                    "${modelProject.absolutePath}/build.gradle",
                    true
                )

                this.textFileHandler.replaceLine(
                    10,
                    "rootProject.name = '${this.name}'",
                    "${modelProject.absolutePath}/settings.gradle",
                    true
                )

                this.addLibs(modelProject.absolutePath)

                this.locateScriptsInProject(this.metadata["scriptsToExecute"] as List<String>, modelProject.absolutePath)

                Files.move(Paths.get(modelProject.name), Paths.get(this.name))
            }
        }
    }

    private fun addLibs(projectName: String) {
        print("\u001B[38;5;155mRequesting an optimized process manager... \u001B[0m")

        File("$projectName/libs").mkdir()

        downloadFile(
            URL(app.env("OPTIMIZED_PROCESS_MANAGER_URL")),
            "$projectName/libs/octopus-${app.env("OCTOPUS_VERSION")}.jar"
        )

        println("\u001B[38;5;155m✔\u001B[0m")

        println("\u001B[38;5;155mProcess Manager located.\u001B[0m")
    }

    private fun locateScriptsInProject(scripts: List<String>, targetProjectPath: String) {
        if (scripts.isEmpty()) {
            println("\u001B[38;5;229mNo scripts configured...\u001B[0m")

            return
        }

        scripts.forEach { script ->
            val scriptName = if (!isRelativeScriptFile(script)) {
                script.substring(script.lastIndexOf("/") + 1)
            } else {
                script
            }

            print("$scriptName...")

            if (Files.notExists(Paths.get("$targetProjectPath/src/main/kotlin/scripts/$scriptName"))) {
                val scriptTargetPath = "$targetProjectPath/src/main/kotlin/scripts/$scriptName"

                this.locateScript(script, scriptTargetPath)

                val splitPartsByKebabCase = scriptName.substring(0, scriptName.indexOf(".")).split("_")

                val splitPartsBySnakeCase = scriptName.substring(0, scriptName.indexOf(".")).split("-")

                when {
                    splitPartsBySnakeCase.size > 1 -> {
                        this.changeScriptVariable(splitPartsBySnakeCase, scriptTargetPath)
                    }
                    splitPartsByKebabCase.size > 1 -> {
                        this.changeScriptVariable(splitPartsByKebabCase, scriptTargetPath)
                    }
                    else -> {
                        this.changeScriptVariable(splitPartsByKebabCase, scriptTargetPath)
                    }
                }

                print("The $ANSI_GREEN_155$script$ANSI_RESET was added.")
            }

            println("\u001B[38;5;155m..[ok]\u001B[0m")
        }

        println("\u001B[38;5;155mScripts located.\u001B[0m")
    }

    private fun changeScriptVariable(wordsOfVariableName: List<String>, scriptTargetPath: String) {
        var finalValName = ""

        if (wordsOfVariableName.isNotEmpty()) {
            wordsOfVariableName.forEachIndexed lit@{ index, value ->
                if (index != 0) {
                    finalValName += value.substring(0, 1).toUpperCase().plus(value.substring(1))

                    return@lit
                }

                finalValName += value
            }

            val callableName = this.textFileHandler.getContentBetweenContent("val", ":", filePath = scriptTargetPath)

            val callableLine = this.textFileHandler.getNumberLineFor("val${callableName[0]}: (", scriptTargetPath)

            val callableLineContent = this.textFileHandler.getContentForLine(callableLine, scriptTargetPath)

            val renamedCallable = callableLineContent.replace(callableName[0], " $finalValName")

            this.textFileHandler.replaceLine(callableLine, renamedCallable, scriptTargetPath, true);
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun locateScript(scriptPath: String, destinationPath: String) {
        val file = this.fileHandler.load(scriptPath)

        val tmpPath = createTempFile("k-s-", ".kt")

        val tmpFile = File(tmpPath.toAbsolutePath().toString())

        tmpFile.printWriter().use { writer ->
            var lineNumber = 0

            file.forEachLine { line ->
                if (lineNumber == 0) {
                    writer.println("package scripts\n\n$line")
                } else {
                    writer.println(line)
                }

                lineNumber++
            }
        }

        tmpFile.renameTo(File(destinationPath))
    }
}
