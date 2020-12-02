package com.koupper.octopus.managers

interface ProjectManager {
    fun buildFrom(name: String, constituents: Map<String, Any>) : ProjectManager

    fun projectName() : String

    fun projectConstituents() : Map<String, Any>
}