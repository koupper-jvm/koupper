package com.koupper.octopus.process

interface Process {
    fun register(name: String, metadata: Map<String, Any> = emptyMap()) : Process

    fun moduleName() : String

    fun run()
}