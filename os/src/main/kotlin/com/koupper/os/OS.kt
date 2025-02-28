package com.koupper.os

import com.koupper.shared.getProperty
import java.io.File

val envs = mutableListOf<String>()

fun env(variableName: String): String {
    if (System.getenv("GLOBAL_ENV_FILE") != null) {
        if (File(System.getenv("GLOBAL_ENV_FILE")).getProperty(variableName) === "undefined") {
            throw Exception("The $variableName is not present in global config file.")
        }
    }

    var envValue = System.getenv(variableName) ?: "undefined"

    if (envValue === "undefined") {
        envValue = try {
            val envValueOnFile = File(".env").getProperty(variableName)

            if (envValueOnFile === "undefined") {
                ""
            } else {
                envValueOnFile
            }
        } catch (e: Exception) {
            ""
        }
    }

    envs.add(envValue)

    return envValue
}

fun setGlobalConfig(path: String) {
    System.setProperty("GLOBAL_ENV_FILE", path)
}

fun main() {
    print("Os class")
}