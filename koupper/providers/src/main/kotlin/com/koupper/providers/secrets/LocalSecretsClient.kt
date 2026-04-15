package com.koupper.providers.secrets

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SecretsConfig(
    val filePath: String,
    val envPrefix: String = "SECRET_",
    val persistWrites: Boolean = true,
    val requireFile: Boolean = false
)

class LocalSecretsClient(
    private val config: SecretsConfig
) : SecretsClient {
    private val mapper = jacksonObjectMapper()
    private val cache = ConcurrentHashMap<String, String>()
    private val file by lazy { File(config.filePath) }

    init {
        loadFromFile()
    }

    override fun get(key: String): String {
        return getOrNull(key) ?: error("secret '$key' was not found")
    }

    override fun getOrNull(key: String): String? {
        val envName = config.envPrefix + key.uppercase().replace("-", "_")
        val envValue = System.getenv(envName)
        if (!envValue.isNullOrBlank()) {
            return envValue
        }
        return cache[key]
    }

    override fun getJson(key: String): Map<String, Any?> {
        val raw = get(key)
        return mapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
    }

    override fun put(key: String, value: String) {
        cache[key] = value
        if (config.persistWrites) {
            persistToFile()
        }
    }

    override fun exists(key: String): Boolean {
        return getOrNull(key) != null
    }

    override fun delete(key: String): Boolean {
        val removed = cache.remove(key) != null
        if (removed && config.persistWrites) {
            persistToFile()
        }
        return removed
    }

    override fun list(): Set<String> {
        return cache.keys.toSet()
    }

    private fun loadFromFile() {
        if (!file.exists()) {
            if (config.requireFile) {
                error("secrets file does not exist: ${file.absolutePath}")
            }
            return
        }

        if (file.readText().isBlank()) {
            return
        }

        val parsed = mapper.readValue(file.readText(), object : TypeReference<Map<String, String>>() {})
        cache.putAll(parsed)
    }

    private fun persistToFile() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cache))
    }
}
