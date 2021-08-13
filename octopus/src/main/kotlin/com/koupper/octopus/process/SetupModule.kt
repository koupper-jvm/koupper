package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.interfaces.Container
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.TextFileHandler
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess

class SetupModule(private val container: Container) : Process {
    override lateinit var name: String
    override var metadata: MutableMap<String, Any> = mutableMapOf()
    override lateinit var moduletype: String

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
            println("\n$ANSI_YELLOW_229 A module named '$name' already exist. $ANSI_RESET \n")

            exitProcess(0)
        }

        val fileHandler = this.container.createInstanceOf(FileHandler::class, "FileHandlerImpl")

        val textFileHandler = this.container.createInstanceOf(TextFileHandler::class)

        if (this.moduletype.equals("FRONT", true)) {
            fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/front-module.zip", this.name)

            File(this.name).delete()
        } else if (this.moduletype.equals("BACK", true)) {
            fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/back-module.zip", this.name)

            File(this.name).delete()

            val fileHandler = this.container.createInstanceOf(TextFileHandler::class)
            fileHandler.replaceLine(10, "rootProject.name = '${this.name}'", "${Paths.get("").toAbsolutePath()}/${this.name}/settings.gradle")

            val processManager = textFileHandler.read("resource://.env")


        }
    }
}
