package com.koupper.octopus

class Config {
    private val scripts : MutableList<String> = mutableListOf()

    fun runScript(scriptPath: String) : Config {
        scripts.add(scriptPath)

        return this
    }

    fun listScripts() : MutableList<String> {
        return this.scripts
    }
}