package com.koupper.providers.files

interface YmlFileHandler {
    fun readFrom(filePath: String): Map<String, Any>
}