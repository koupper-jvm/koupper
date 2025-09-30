package com.koupper.shared.runtime

interface ScriptBackend {
    fun eval(code: String): Any?
    val classLoader: ClassLoader
}
