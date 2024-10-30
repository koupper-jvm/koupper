package com.koupper.providers.files

interface JSONFileHandler<T> {
    fun readFrom(filePath: String): JSONFileHandler<T>
    fun read(jsonTxt: String): JSONFileHandler<T>
    fun getText(): String
    fun toJsonString(data: T): String
    fun mapToJsonString(data: Map<String, Any>?): String
    fun listOfMapsToJsonString(data: List<Map<String, Any>>?): String
    fun mapToJsonString(data: List<Map<String, Any>>): String
}
