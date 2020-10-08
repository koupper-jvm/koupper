package com.koupper.octopus

class Config : ScriptManager {
    private val scripts: MutableMap<String, Map<String, Any>> = mutableMapOf()
    private var deploymentType = DeploymentType.NONE
    private lateinit var deployableName: String

    override fun runScript(scriptPath: String, params: Map<String, Any>): ScriptManager {
        this.scripts[scriptPath] = params

        return this
    }

    override fun listScriptsToExecute(): MutableMap<String, Map<String, Any>> {
        return this.scripts
    }

    override fun deployType(): DeploymentType {
        return this.deploymentType
    }

    override fun deployableName(): String {
        return this.deployableName
    }

    override fun toDeployableProjectNamed(name: String): ScriptManager {
        this.deployableName = name
        this.deploymentType = DeploymentType.JAR

        return this
    }
}