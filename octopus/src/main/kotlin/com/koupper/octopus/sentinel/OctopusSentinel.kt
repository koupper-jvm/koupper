package com.koupper.octopus.sentinel

import com.koupper.providers.ServiceProvider
import com.koupper.providers.ServiceProviderManager
import java.io.File
import kotlin.reflect.KClass

class OctopusSentinel(private val projectDir: File) {
    private val providerManager = ServiceProviderManager()

    /**
     * Scans the project source code for usage of Koupper Service Providers.
     */
    fun scanUsedProviders(): List<String> {
        val usedProviders = mutableSetOf<String>()
        val srcDir = File(projectDir, "src")
        if (!srcDir.exists()) return emptyList()

        srcDir.walkTopDown().filter { it.extension == "kt" || it.extension == "kts" }.forEach { file ->
            val content = file.readText()
            // Look for imports or class references of known providers
            providerManager.listProviders().forEach { providerClass ->
                val name = providerClass.simpleName ?: ""
                if (content.contains(name)) {
                    usedProviders.add(providerClass.qualifiedName ?: "")
                }
            }
        }
        return usedProviders.toList()
    }

    /**
     * Synchronizes the build.gradle(.kts) file with the dependencies required
     * by the detected Service Providers.
     */
    fun syncDependencies() {
        val usedProviderNames = scanUsedProviders()
        val allProviders = providerManager.listProviders().map { 
            it.constructors.elementAt(0).call() as ServiceProvider 
        }

        val requiredDependencies = allProviders
            .filter { it.javaClass.name in usedProviderNames }
            .flatMap { it.externalDependencies() }
            .distinct()

        if (requiredDependencies.isEmpty()) return

        val buildFile = File(projectDir, "build.gradle.kts").let { 
            if (it.exists()) it else File(projectDir, "build.gradle") 
        }

        if (!buildFile.exists()) return

        com.koupper.logging.GlobalLogger.log.info { "🛡️ Octopus Sentinel: Syncing ${requiredDependencies.size} dependencies for project ${projectDir.name}" }
        
        injectDependencies(buildFile, requiredDependencies)
    }

    private fun injectDependencies(buildFile: File, dependencies: List<String>) {
        val lines = buildFile.readLines().toMutableList()
        val dependenciesIndex = lines.indexOfFirst { it.trim().startsWith("dependencies {") }
        
        if (dependenciesIndex == -1) return

        dependencies.forEach { dep ->
            val depLine = "    implementation(\"$dep\")"
            if (lines.none { it.contains(dep) }) {
                lines.add(dependenciesIndex + 1, depLine)
                com.koupper.logging.GlobalLogger.log.info { "➕ Added dependency: $dep" }
            }
        }

        buildFile.writeText(lines.joinToString("\n"))
    }
}
