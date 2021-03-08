package com.koupper.octopus.process

import com.koupper.configurations.utilities.ANSIColors.ANSI_RED
import com.koupper.configurations.utilities.ANSIColors.ANSI_RESET
import java.io.File
import kotlin.system.exitProcess

class ModuleConfiguration : ModuleProcess {
    lateinit var name: String
    private val metadata: MutableMap<String, Any> = mutableMapOf()

    override fun setup(name: String, metadata: Map<String, Any>): ModuleProcess {
        this.name = name
        this.metadata.putAll(this.metadata)

        return this
    }

    override fun moduleName(): String {
        return this.name
    }

    override fun build() {
        val modulePath = File(name)

        if (modulePath.exists()) {
            println("The folder named $ANSI_RED$name} already exist. Do you want to use this as your module? (y/n).$ANSI_RESET")

            exitProcess(0)
        }

        this.scan()
    }

    private fun scan() {

    }
}
