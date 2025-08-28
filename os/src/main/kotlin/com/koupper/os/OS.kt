package com.koupper.os

import com.koupper.shared.getProperty
import java.io.File

val envs = mutableListOf<String>()

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

    if (value == null && System.getenv("GLOBAL_ENV_FILE") != null) {
        val fromGlobal = File(System.getenv("GLOBAL_ENV_FILE")).getProperty(variableName)
        value = if (fromGlobal != "undefined") fromGlobal else null
    }

    if (value == null) {
        val fromDotEnv = try {
            File("${if (context != null) context + File.separator else ""}.env").getProperty(variableName)
        } catch (_: Exception) { "undefined" }

        value = if (fromDotEnv != "undefined") fromDotEnv else null
    }

    if (value == null) {
        if (required) {
            throw Exception("The $variableName should be present in environment or .env")
        } else {
            value = default // por defecto "", útil para DYNAMO_URL
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

fun setGlobalConfig(path: String) {
    System.setProperty("GLOBAL_ENV_FILE", path)
}

fun main() {
    print("Os class")
}