package com.koupper.octopus.process

class ModuleAnalyzer : Process {
    override lateinit var name: String
    override lateinit var metadata: MutableMap<String, Any>
    override lateinit var moduletype: String

    override fun register(name: String, metadata: Map<String, Any>): Process {
        TODO("Not yet implemented")
    }

    override fun processName(): String {
        TODO("Not yet implemented")
    }

    override fun processType(): String {
        TODO("Not yet implemented")
    }

    override fun run() {
        TODO("Not yet implemented")
    }
}