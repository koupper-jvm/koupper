package com.koupper.cli

interface Wizard {
    fun init(args: Map<String, String> = emptyMap())
}
