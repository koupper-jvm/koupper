package com.koupper.container.interfaces

interface Environment {
    fun env(variableName: String): String
}