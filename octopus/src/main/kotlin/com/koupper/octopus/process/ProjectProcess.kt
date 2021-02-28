package com.koupper.octopus.process

interface ProjectProcess {
    fun buildFrom(name: String, constituents: Map<String, Any>) : ProjectProcess

    fun projectName() : String

    fun projectConstituents() : Map<String, Any>
}