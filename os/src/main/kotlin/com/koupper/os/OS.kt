package com.koupper.os

import com.koupper.shared.getProperty
import java.io.File

val envs = mutableListOf<String>()

fun env(variableName: String, context: String? = null, variables: Map<String, String>? = null): String {
    var envValue = System.getenv(variableName) ?: "undefined"

    if (System.getenv("GLOBAL_ENV_FILE") != null) {
        envValue = File(System.getenv("GLOBAL_ENV_FILE")).getProperty(variableName)
    }

    if (envValue === "undefined") {
        envValue = try {
            val envValueOnFile = File("${if(context != null) context + File.separator else ""}.env").getProperty(variableName)

            if (envValueOnFile === "undefined") {
                envs.add(envValue)
                throw Exception("The $variableName should be present in: an environment variable||an env file (.env) to use this provider")
            } else {
                envValueOnFile
            }
        } catch (e: Exception) {
            envs.add(envValue)
            throw Exception("The $variableName should be present in: an environment variable||an env file (.env) for using this provider")
        }
    }

    if (!variables.isNullOrEmpty()) {
        envValue = envValue.replace(Regex("\\$(\\w+)")) { matchResult ->
            val key = matchResult.groupValues[1]
            variables?.get(key) ?: matchResult.value
        }
    }

    return envValue
}

fun setGlobalConfig(path: String) {
    System.setProperty("GLOBAL_ENV_FILE", path)
}

fun main() {
    print("Os class")
}