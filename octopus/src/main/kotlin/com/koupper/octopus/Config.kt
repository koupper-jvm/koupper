package com.koupper.octopus

import com.koupper.container.interfaces.ScriptManager

class Config : ScriptManager {
    private val scripts : MutableMap<String, Map<String, Any>> = mutableMapOf()

    override fun runScript(scriptPath: String, params: Map<String, Any>) : ScriptManager {
        this.scripts[scriptPath] = params

        return this
    }

    override fun listScripts() : MutableMap<String, Map<String, Any>> {
        return this.scripts
    }
}