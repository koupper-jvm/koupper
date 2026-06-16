package com.koupper.os

import com.koupper.shared.getProperty
import com.koupper.logging.LoggerFactory
import java.io.File

val envs = mutableListOf<String>()
private val logger = LoggerFactory.get("Koupper-OS")

/** Set by the Octopus runtime when executing a script (the script's working directory). */
var scriptContext: String? = null

fun env(
    variableName: String,
    context: String? = null,
    variables: Map<String, String>? = null,
    required: Boolean = true,
    allowEmpty: Boolean = false,
    default: String = ""
): String {
    val sysEnv = System.getenv()
    var value: String? = if (sysEnv.containsKey(variableName)) sysEnv[variableName] else null

    val globalEnvFileRaw = System.getProperty("GLOBAL_ENV_FILE")
        ?: System.getenv("GLOBAL_ENV_FILE")

    val globalEnvFile = globalEnvFileRaw?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")

    if (globalEnvFileRaw != null) {
        logger.debug { "Found GLOBAL_ENV_FILE variable: '$globalEnvFileRaw'" }
        logger.debug { "Cleaned path: '$globalEnvFile'" }
    }

    if (value == null && !globalEnvFile.isNullOrBlank()) {
        val file = File(globalEnvFile)
        if (file.exists()) {
            val fromGlobal = file.getProperty(variableName)
            value = if (fromGlobal != "undefined") fromGlobal else null
            if (value != null) {
                logger.debug { "Variable '$variableName' found in GLOBAL_ENV_FILE." }
            }
        } else {
            logger.warn { "GLOBAL_ENV_FILE at '$globalEnvFile' DOES NOT EXIST." }
        }
    }

    if (value == null) {
        // explicit context > scriptContext (set by Octopus per script run) > JVM cwd
        val searchRoot = context?.takeIf { it.isNotBlank() }
            ?: scriptContext?.takeIf { it.isNotBlank() }
            ?: "."
        val startDir = File(searchRoot)
        val fromDotEnv = generateSequence(startDir.canonicalFile) { it.parentFile }
            .mapNotNull { dir ->
                runCatching { File(dir, ".env").getProperty(variableName).takeIf { it != "undefined" } }
                    .getOrNull()
            }
            .firstOrNull()
        value = fromDotEnv
    }

    if (value == null) {
        if (required) {
            throw Exception("The $variableName should be present in environment or .env")
        } else {
            value = default
        }
    }

    if (!allowEmpty && value.isEmpty()) {
        if (required) {
            throw Exception("The $variableName must not be empty")
        } else {
            value = default
        }
    }

    if (!variables.isNullOrEmpty()) {
        value = value.replace(Regex("\\$(\\w+)")) { mr ->
            variables[mr.groupValues[1]] ?: mr.value
        }
    }

    return value
}

fun envOptional(name: String, default: String = ""): String =
    env(name, required = false, default = default)

fun envBool(name: String, default: Boolean = false): Boolean =
    env(name, required = false, default = default.toString()).equals("true", ignoreCase = true)

fun setGlobalConfig(path: String) {
    System.setProperty("GLOBAL_ENV_FILE", path)
}

fun main() {
    print("Os class")
}
