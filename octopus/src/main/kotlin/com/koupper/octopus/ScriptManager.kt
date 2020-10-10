package com.koupper.octopus

interface ScriptManager : DeploymentManager {
    fun runScript(scriptPath: String, params: Map<String, Any> = emptyMap()) : ScriptManager

    fun listScriptsToExecute() : MutableMap<String, Map<String, Any>>

    fun deployType() : DeploymentType

    fun deployableName() : String

    override fun toDeployableProjectNamed(name: String): ScriptManager
}
