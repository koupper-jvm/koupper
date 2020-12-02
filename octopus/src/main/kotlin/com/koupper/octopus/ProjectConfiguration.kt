package com.koupper.octopus

import com.koupper.octopus.managers.ProjectManager

class ProjectConfiguration : ProjectManager {
    lateinit var name: String
    private val constituents: MutableMap<String, Any> = mutableMapOf()

    override fun buildFrom(name: String, constituents: Map<String, Any>): ProjectManager {
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