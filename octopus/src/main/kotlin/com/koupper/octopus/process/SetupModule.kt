package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import com.koupper.configurations.utilities.ANSIColors.ANSI_YELLOW_229
import com.koupper.container.interfaces.Container
import com.koupper.providers.files.FileHandler
import java.io.File
import kotlin.system.exitProcess

class SetupModule(private val container: Container) : Process {
    private lateinit var name: String
    private val metadata: MutableMap<String, Any> = mutableMapOf()

    override fun register(name: String, metadata: Map<String, Any>): Process {
        this.name = name
        this.metadata.putAll(metadata)

        return this
    }

    override fun moduleName(): String {
        return this.name
    }

    override fun run() {
        if (File(name).exists()) {
            println("\n$ANSI_YELLOW_229 A module named '$name' already exist. $ANSI_RESET \n")

            exitProcess(0)
        }

        val moduleType = this.metadata["moduleType"]

        if (moduleType != null && (moduleType as String).equals("FRONT", true)) {
            val fileHandler = this.container.createInstanceOf(FileHandler::class, "FileHandlerImpl")

            fileHandler.unzipFile("https://lib-installer.s3.amazonaws.com/front-module.zip", this.name)

            File(this.name).delete()
        }
    }
}
