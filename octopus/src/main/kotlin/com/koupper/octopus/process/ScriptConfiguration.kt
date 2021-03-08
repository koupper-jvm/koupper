package com.koupper.octopus.process

class ScriptConfiguration : ScriptProcess {
    private val params: MutableMap<String, Map<String, Any>> = mutableMapOf()

    override fun run(scriptPath: String, params: Map<String, Any>): ScriptProcess {
        this.params[scriptPath] = params

        return this
    }

    override fun listScriptsToExecute(): MutableMap<String, Map<String, Any>> {
        return this.params
    }

    override fun buildFrom(projectName: String, resources: Map<String, Any>): ModuleProcess {
        return ModuleConfiguration().setup(projectName, mapOf(
                "scripts" to params
        ))
    }
}