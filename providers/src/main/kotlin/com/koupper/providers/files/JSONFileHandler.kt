package com.koupper.providers.files

interface JSONFileHandler <String, out T> {
    fun readFrom(filePath: String): JSONFileHandler<String, T>

    fun read(jsonTxt: String): JSONFileHandler<String, T>

    fun getText() : String
}