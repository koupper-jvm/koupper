package com.koupper.providers.parsing.extensions

import com.koupper.providers.parsing.TextParser

fun TextParser.splitKeyValue(delimiter: String): Map<String?, String?> {
    val properties = mutableMapOf<String?, String?>()

    this.getText().lines().forEach { line ->
        if (line.isNotEmpty()) {
            val key = line.substring(0, line.indexOf(delimiter))

            val value = line.substring(line.indexOf(delimiter) + 1)

            properties[key] = value
        }
    }

    return properties
}
