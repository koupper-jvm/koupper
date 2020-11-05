package com.koupper.providers.parsing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class JsonToObject<out T> : TextJsonParser<String, T> {
    val mapper = jacksonObjectMapper()
    private lateinit var jsonString: String


    override fun readFromPath(path: String): String {
        TODO("Not yet implemented")
    }

    override fun readFromURL(url: String): String {
        TODO("Not yet implemented")
    }

    override fun readFromResource(path: String): String {
        TODO("Not yet implemented")
    }

    override fun bind(data: Map<String, String?>, content: StringBuilder): StringBuilder {
        TODO("Not yet implemented")
    }

    override fun getText(): String {
        return this.jsonString
    }

    inline fun <reified T> toType(): T {
        return mapper.readValue(this.getText())
    }

    override fun load(jsonString: String) {
        this.jsonString = jsonString
    }
}