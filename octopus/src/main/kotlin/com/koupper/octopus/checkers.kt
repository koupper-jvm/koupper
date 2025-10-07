package com.koupper.octopus

import java.io.File

val currentClassPath: String by lazy {
    val base = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map { File(it) }

    val extra = listOf(
        File("octopus/build/classes/kotlin/main"),
        File("container/build/classes/kotlin/main"),
        File("shared/build/classes/kotlin/main"),
        File("providers/build/classes/kotlin/main"),
        File("configurations/build/classes/kotlin/main"),
        File("os/build/classes/kotlin/main"),
        File("orchestrator-core/build/classes/kotlin/main"),
        File("logging/build/classes/kotlin/main")
    )

    (base + extra)
        .filter { it.exists() }
        .joinToString(File.pathSeparator) { it.absolutePath }
}

