package com.koupper.providers.parsing

import java.lang.StringBuilder

interface TextParser {
    /**
     * Read from Path
     */
    fun readFromPath(path: String): String

    /**
     * Read from URL
     */
    fun readFromURL(url: String): String

    /**
     * Read from resource folder within project
     */
    fun readFromResource(path: String): String

    /**
     * Bind a map of properties to string content
     */
    fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder

    /**
     * The string representation of the loaded text
     */
    fun getText(): String
}
