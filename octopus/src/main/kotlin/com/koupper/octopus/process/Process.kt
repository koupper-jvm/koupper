package com.koupper.octopus.process

interface Process {
    fun processName() : String

    fun processType() : String

    fun run()
}