package com.koupper.octopus.process

import com.koupper.container.interfaces.Container

class SetupModule(container: Container) : Process {
    lateinit var name: String
    private val metadata: MutableMap<String, Any> = mutableMapOf()

    override fun register(name: String, metadata: Map<String, Any>): Process {
        this.name = name
        this.metadata.putAll(this.metadata)

        return this
    }

    override fun moduleName(): String {
        return this.name
    }

    override fun run() {

    }
}
