package com.koupper.providers.files

interface JsonFileHandler<T> {
    fun readFrom(filePath: String): JsonFileHandler<T>
    fun read(jsonTxt: String): JsonFileHandler<T>
    fun getText(): String
    fun toJsonString(data: T): String
    fun mapToJsonString(data: Map<String, Any>?): String
    fun listOfMapsToJsonString(data: List<Map<String, Any>>?): String
}
