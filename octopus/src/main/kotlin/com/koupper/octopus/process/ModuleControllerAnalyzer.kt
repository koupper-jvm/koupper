package com.koupper.octopus.process

import java.io.File

fun extractServerPort(moduleDir: File): String? {
    val setupFile = findKtFileRecursively(
        File(moduleDir, "src/main/kotlin/server"),
        "Setup"
    ) ?: return null

    val content = setupFile.readText()

    return Regex("""const val PORT\s*=\s*(\d+)""")
        .find(content)
        ?.groupValues?.get(1)
}

fun extractContextPath(moduleDir: File): String? {
    val setupFile = findKtFileRecursively(
        File(moduleDir, "src/main/kotlin"),
        "Setup"
    ) ?: return null

    val content = setupFile.readText()

    val match = Regex("""const val CONTEXT_PATH\s*=\s*"([^"]+)"""")
        .find(content)

    return match?.groupValues?.get(1)
}
