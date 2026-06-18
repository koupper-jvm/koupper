package com.koupper.octopus

import com.koupper.container.app

class EmbeddedOctopus private constructor() {
    private val octopus: Octopus by lazy {
        val o = Octopus(app)
        o.registerBuildInServicesProvidersInContainer()
        o
    }

    fun runScript(scriptContent: String): String {
        var captured = ""
        octopus.run<String>(
            context = ".",
            scriptPath = null,
            sentence = scriptContent,
            params = null,
            callable = null
        ) { output ->
            captured = output
        }
        return captured
    }

    companion object {
        private var instance: EmbeddedOctopus? = null

        fun get(): EmbeddedOctopus {
            if (instance == null) {
                instance = EmbeddedOctopus()
                instance!!.octopus
            }
            return instance!!
        }
    }
}
