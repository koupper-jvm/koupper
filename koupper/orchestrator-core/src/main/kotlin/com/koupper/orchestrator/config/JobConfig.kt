package com.koupper.orchestrator.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

object JobConfig {
    fun loadOrFail(context: String, configId: String? = null, configFileName: String? = "jobs.json"): JobConfiguration {
        val safeConfigName = sanitizeFileName(configFileName ?: "jobs.json")
        return JobConfiguration(configurations = createListOfConfigurations(context, configId, safeConfigName))
    }

    private fun createListOfConfigurations(context: String, configId: String? = null, configFileName: String): List<JobConfiguration> {
        val configs = loadConfigs(context, configId, configFileName)
        if (configs.isEmpty()) return emptyList()

        val mapper = jacksonObjectMapper()
        return configs.map { mapper.convertValue(it, JobConfiguration::class.java) }
    }

    private fun loadConfigs(context: String, configId: String? = null, configFileName: String): List<Map<String, Any?>> {
        val jobsJson = File("$context${File.separator}${configFileName}")
        if (!jobsJson.exists()) return emptyList()

        val text = jobsJson.readText().trim()
        if (text.isEmpty()) return emptyList()

        val mapper = jacksonObjectMapper()
        val raw: List<Map<*, *>> = if (text.startsWith("[")) {
            mapper.readValue(
                text,
                mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java)
            )
        } else {
            listOf(mapper.readValue(text, Map::class.java))
        }

        val configs = raw.map { entry -> entry.mapKeys { it.key.toString() } }

        return if (configId.isNullOrBlank()) configs else configs.filter { it["id"]?.toString() == configId }
    }

    private fun sanitizeFileName(fileName: String): String {
        var clean = fileName.trim().trimStart('/', '\\')
        if (!clean.endsWith(".json", ignoreCase = true)) {
            clean += ".json"
        }
        return clean
    }
}
