package com.koupper.providers.parsing

import java.io.File
import java.lang.StringBuilder
import java.nio.file.Paths

class TextParserEnvPropertiesTemplate : TextParser {
    private lateinit var text: String

    override fun readFromPath(path: String): StringBuilder {
        this.text = File(if (isSingleFileName(path)) {
            Paths.get("").toAbsolutePath().toString() + "/$path "
        } else {
            path
        }.trim()).readText(Charsets.UTF_8)

        return StringBuilder(this.text)
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder {
        TODO("Not yet implemented")
    }

    override fun getText(): String {
        return this.text
    }
}
