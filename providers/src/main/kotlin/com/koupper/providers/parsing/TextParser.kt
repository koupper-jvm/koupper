package com.koupper.providers.parsing

import java.lang.StringBuilder

interface TextParser {
    fun readFromPath(path: String): StringBuilder

    fun readFromURL(path: String): String

    fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder

    fun getText(): String
}
