package io.kup.providers.parsing.extensions

import io.kup.providers.parsing.TextParser

fun TextParser.splitKeyValue(delimiter: Regex): Map<String?, String?> {
    val properties = mutableMapOf<String?, String?>()

    this.getText().lines().forEach { line ->
        val key = line.split(delimiter)[0]

        val value = line.split(delimiter)[1]

        properties[key] = value
    }

    return properties
}
