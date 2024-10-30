package com.koupper.providers.files

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.container.app
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.FileHandlerImpl
import com.koupper.providers.files.JsonFileHandler

class JsonFileHandlerImpl<T> : JsonFileHandler<T> {
    private val fileHandler: FileHandler = FileHandlerImpl()
    private var jsonTxt: String? = null

    override fun readFrom(filePath: String): JsonFileHandler<T> {
        jsonTxt = fileHandler.load(filePath).readText(Charsets.UTF_8)
        return this
    }

    override fun read(jsonTxt: String): JsonFileHandler<T> {
        this.jsonTxt = jsonTxt
        return this
    }

    override fun getText(): String {
        return jsonTxt ?: throw IllegalStateException("JSON text is not initialized.")
    }

    override fun toJsonString(data: T): String {
        return jacksonObjectMapper().writeValueAsString(data)
    }

    override fun mapToJsonString(data: Map<String, Any>?): String {
        return jacksonObjectMapper().writeValueAsString(data)
    }

    override fun mapToJsonString(data: List<Map<String, Any>>): String {
        return jacksonObjectMapper().writeValueAsString(data)
    }

    override fun listOfMapsToJsonString(data: List<Map<String, Any>>?): String {
        return jacksonObjectMapper().writeValueAsString(data)
    }
}

inline fun <reified T> JsonFileHandler<T>.toType(): T {
    return try {
        // Asegúrate de pasar el tipo correcto a jacksonObjectMapper
        jacksonObjectMapper().readValue<T>(this.getText())
    } catch (e: Exception) {
        throw JsonParseException("Failed to parse JSON to type ${T::class.simpleName}: ${e.message}", e)
    }
}

class JsonParseException(message: String, cause: Throwable) : Exception(message, cause)
