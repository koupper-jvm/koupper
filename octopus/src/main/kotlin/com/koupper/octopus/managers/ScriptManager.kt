package com.koupper.octopus.managers

interface ScriptManager {
    fun run(scriptPath: String, params: Map<String, Any> = emptyMap()) : ScriptManager

    fun listScriptsToExecute() : MutableMap<String, Map<String, Any>>

    fun buildFrom(projectName: String, resources: Map<String, Any> = emptyMap()) : ProjectManager
}
