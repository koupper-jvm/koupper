package io.kup.providers.parsing

import java.lang.StringBuilder

interface TextParser {
    fun readFromPath(path: String): StringBuilder

    fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder

    fun getText(): String
}
