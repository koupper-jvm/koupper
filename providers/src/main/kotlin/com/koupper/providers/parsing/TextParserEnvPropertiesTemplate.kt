package com.koupper.providers.parsing

import java.io.File
import java.lang.StringBuilder

class TextParserEnvPropertiesTemplate : TextParser {
    private lateinit var text: String

    override fun readFromPath(path: String): StringBuilder {
        this.text = File(path).readText(Charsets.UTF_8)

        return StringBuilder(this.text)
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder {
        TODO("Not yet implemented")
    }

    override fun getText(): String {
        return this.text
    }
}
