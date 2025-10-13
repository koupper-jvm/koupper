package com.koupper.orchestrator.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Paths

object JobConfig {
    fun loadOrFail(context: String, configId: String? = null): JobConfiguration {
        return JobConfiguration(configurations = this.createListOfConfigurations(context, configId))
    }

    private fun createListOfConfigurations(context: String, configId: String? = null): List<JobConfiguration> {
        val configs = loadConfigs(context, configId)
        if (configs.isEmpty()) return emptyList()

        val mapper = jacksonObjectMapper()
        return configs.map { mapper.convertValue(it, JobConfiguration::class.java) }
    }

    private fun loadConfigs(context: String, configId: String? = null): List<Map<String, Any?>> {
        val jobsJson = File("$context/jobs.json")
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

        val configs = raw.map { entry ->
            entry.mapKeys { it.key.toString() }
        }

        return if (configId.isNullOrBlank()) {
            configs.filter { !it["ignore-on-running"]?.toString().toBoolean() }
        } else {
            configs.filter { it["id"]?.toString() == configId }
        }
    }
}
