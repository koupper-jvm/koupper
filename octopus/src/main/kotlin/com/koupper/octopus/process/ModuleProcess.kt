package com.koupper.octopus.process

interface ModuleProcess {
    fun buildFrom(name: String, constituents: Map<String, Any>) : ModuleProcess

    fun projectName() : String

    fun projectConstituents() : Map<String, Any>
}