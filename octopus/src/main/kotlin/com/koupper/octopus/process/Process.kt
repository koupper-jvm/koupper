package com.koupper.octopus.process

interface Process {
    fun register(name: String, moduleType: String, version: String, packageName: String, scripts: Map<String, String>) : Process

    fun processName() : String

    fun processType() : String

    fun run()
}