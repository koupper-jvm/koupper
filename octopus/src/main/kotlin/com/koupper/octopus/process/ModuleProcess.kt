package com.koupper.octopus.process

interface ModuleProcess {
    fun setup(name: String, metadata: Map<String, Any>) : ModuleProcess

    fun moduleName() : String

    fun build()
}