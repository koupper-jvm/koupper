package com.koupper.providers.parsing

import java.io.File
import java.lang.StringBuilder

class TextParserHtmlEmailTemplate : TextParser {
    private lateinit var text: String

    override fun readFromPath(path: String): StringBuilder {
        this.text = File(path).readText(Charsets.UTF_8)

        return StringBuilder(this.text)
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder {
        data.forEach { key, value ->
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
