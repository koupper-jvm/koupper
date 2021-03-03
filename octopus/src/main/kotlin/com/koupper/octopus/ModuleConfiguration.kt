package com.koupper.octopus

import com.koupper.octopus.process.ModuleProcess

class ModuleConfiguration : ModuleProcess {
    lateinit var name: String
    private val constituents: MutableMap<String, Any> = mutableMapOf()

    override fun buildFrom(name: String, constituents: Map<String, Any>): ModuleProcess {
        this.name = name
        this.constituents.putAll(constituents)

        return this
    }

    override fun projectName(): String {
        return this.name
    }

    override fun projectConstituents(): Map<String, Any> {
        return constituents
    }
}