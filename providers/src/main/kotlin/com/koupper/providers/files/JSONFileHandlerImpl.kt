package com.koupper.providers.files

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class JSONFileHandlerImpl<out T> : JSONFileHandler<String, T> {
    private val fileHandler: FileHandler = FileHandlerImpl()
    private lateinit var jsonTxt: String

    override fun readFrom(filePath: String): JSONFileHandler<String, T> {
        this.jsonTxt = fileHandler.load(filePath).readText(Charsets.UTF_8)

        return this
    }

    override fun read(jsonTxt: String): JSONFileHandler<String, T> {
        this.jsonTxt = jsonTxt

        return this
    }

    override fun getText() : String {
        return this.jsonTxt
    }
}

inline fun <reified T> JSONFileHandler<String, T>.toType() : T {
    return jacksonObjectMapper().readValue(this.getText())
}