package com.koupper.octopus.process

interface Process {
    fun register(name: String, metadata: MutableMap<String, Any>, moduleType: String, version: String, packageName: String, scripts: MutableList<String>) : Process

    fun processName() : String

    fun processType() : String

    fun run()
}