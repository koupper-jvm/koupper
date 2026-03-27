package com.koupper.providers.files

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.container.app

class JSONFileHandlerImpl<T> : JSONFileHandler<T> {
    private val fileHandler: FileHandler = FileHandlerImpl()
    private var jsonTxt: String? = null

    override fun readFrom(filePath: String): JSONFileHandler<T> {
        jsonTxt = fileHandler.load(filePath).readText(Charsets.UTF_8)
        return this
    }

    override fun read(jsonTxt: String): JSONFileHandler<T> {
        this.jsonTxt = jsonTxt
        return this
    }

    override fun getText(): String {
        return jsonTxt ?: throw IllegalStateException("JSON text is not initialized.")
    }

    override fun toJsonString(data: T): String {
        if (data is String) {
            val t = data.trim()
            if ((t.startsWith("{") && t.endsWith("}")) ||
                (t.startsWith("[") && t.endsWith("]"))) {
                return data
            }
        }
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

    override fun mapToJsonString(data: Any?): String {
        return when (data) {
            is String -> data
            else -> jacksonObjectMapper().writeValueAsString(data)
        }
    }
}

inline fun <reified R> JSONFileHandler<*>.toType(): R {
    return try {
        jacksonObjectMapper().readValue<R>(this.getText())
    } catch (e: Exception) {
        throw JsonParseException("Failed to parse JSON to type ${R::class.simpleName}: ${e.message}", e)
    }
}

class JsonParseException(message: String, cause: Throwable) : Exception(message, cause)
