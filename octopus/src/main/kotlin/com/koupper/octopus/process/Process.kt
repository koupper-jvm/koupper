package com.koupper.octopus.process

interface Process {
    var name: String
    var metadata: MutableMap<String, Any>
    var moduletype: String

    fun register(name: String, metadata: Map<String, Any> = emptyMap()) : Process

    fun processName() : String

    fun processType() : String

    fun run()
}