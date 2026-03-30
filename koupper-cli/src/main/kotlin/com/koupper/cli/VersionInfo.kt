package com.koupper.cli

import java.io.File
import java.util.jar.JarFile

object VersionInfo {
    private val userHome: String = System.getProperty("user.home")

    fun cliVersion(): String {
        val codeSource = runCatching {
            CommandManager::class.java.protectionDomain?.codeSource?.location?.toURI()?.path
        }.getOrNull()

        if (!codeSource.isNullOrBlank()) {
            val fromCodeSource = readImplementationVersionFromJar(codeSource)
            if (!fromCodeSource.isNullOrBlank()) {
                return fromCodeSource
            }
        }

        val fromPackage = CommandManager::class.java.`package`?.implementationVersion
        if (!fromPackage.isNullOrBlank()) {
            return fromPackage
        }

        return "dev"
    }

    fun octopusVersion(): String {
        val octopusJarPath = "$userHome/.koupper/libs/octopus.jar"
        val fromManifest = readImplementationVersionFromJar(octopusJarPath)
        if (!fromManifest.isNullOrBlank()) {
            return fromManifest
        }

        return "unknown"
    }

    private fun readImplementationVersionFromJar(path: String): String? {
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.name.endsWith(".jar")) {
            return null
        }

        return runCatching {
            JarFile(file).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Implementation-Version")
            }
        }.getOrNull()
    }
}
