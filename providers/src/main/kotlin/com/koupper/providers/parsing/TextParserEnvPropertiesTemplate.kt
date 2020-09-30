package com.koupper.providers.parsing

import java.io.File
import java.lang.StringBuilder
import java.net.URL
import java.nio.file.Paths

class TextParserEnvPropertiesTemplate(content: String = "NO_CONTENT") : TextParser {
    private var text: String

    init {
        this.text = content
    }

    override fun readFromPath(path: String): StringBuilder {
        this.text = File(if (isSingleFileName(path)) {
            Paths.get("").toAbsolutePath().toString() + "/$path "
        } else {
            path
        }.trim()).readText(Charsets.UTF_8)

        return StringBuilder(this.text)
    }

    override fun readFromURL(url: String): String {
        return URL(url).readText()
    }

    override fun readFromResource(path: String): String {
        this.text = TextParserEnvPropertiesTemplate::class.java.classLoader.getResource(path).readText()

        return this.text
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder {
        TODO("Not yet implemented")
    }

    override fun getText(): String {
        return this.text
    }
}
