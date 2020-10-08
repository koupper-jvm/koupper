package com.koupper.providers.parsing

import java.io.File
import java.lang.StringBuilder
import java.net.URL
import java.nio.file.Paths

val isSingleFileName: (String) -> Boolean = {
    it.contains("^[a-zA-Z0-9]+.kts$".toRegex())
}

class TextParserHtmlEmailTemplate : TextParser {
    private lateinit var text: String

    override fun readFromPath(path: String): String {
        this.text = File(if (isSingleFileName(path)) {
            Paths.get("").toAbsolutePath().toString() + "/$path "
        } else {
            path
        }.trim()).readText(Charsets.UTF_8)

        return this.text
    }

    override fun readFromURL(path: String): String {
        this.text = URL(path).readText()

        return this.text
    }

    override fun readFromResource(path: String): String {
        this.text = TextParserHtmlEmailTemplate::class.java.classLoader.getResource(path).readText()

        return this.text
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder {
        data.forEach { (key, value) ->
            if (content.contains("\\{\\{\\s*\\$${key}\\s*\\}\\}".toRegex())) {
                val parsedVariable = content.replace("\\{\\{\\s*\\$${key}\\s*\\}\\}".toRegex(), value.toString())

                content.clear()

                content.append(parsedVariable)
            }
        }

        return content
    }

    override fun getText(): String {
        return this.text
    }

}
