package com.koupper.octopus.process

interface ScriptProcess {
    fun run(scriptPath: String, params: Map<String, Any> = emptyMap()) : ScriptProcess

    fun listScriptsToExecute() : MutableMap<String, Map<String, Any>>

    fun buildFrom(projectName: String, resources: Map<String, Any> = emptyMap()) : ProjectProcess
}
