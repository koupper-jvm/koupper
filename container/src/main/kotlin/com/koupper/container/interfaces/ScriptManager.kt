package com.koupper.container.interfaces

interface ScriptManager {
    fun runScript(scriptPath: String, params: Map<String, Any> = emptyMap()) : ScriptManager

    fun listScripts() : MutableMap<String, Map<String, Any>>
}
