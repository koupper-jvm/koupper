package com.koupper.octopus

import com.koupper.octopus.managers.ProjectManager
import com.koupper.octopus.managers.ScriptManager

class ScriptConfiguration : ScriptManager {
    private val params: MutableMap<String, Map<String, Any>> = mutableMapOf()

    override fun run(scriptPath: String, params: Map<String, Any>): ScriptManager {
        this.params[scriptPath] = params

        return this
    }

    override fun listScriptsToExecute(): MutableMap<String, Map<String, Any>> {
        return this.params
    }

    override fun buildFrom(projectName: String, resources: Map<String, Any>): ProjectManager {
        return ProjectConfiguration().buildFrom(projectName, mapOf(
                "scripts" to params
        ))
    }
}