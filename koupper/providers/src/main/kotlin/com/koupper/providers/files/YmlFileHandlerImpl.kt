package com.koupper.providers.files

import org.yaml.snakeyaml.Yaml

import java.io.File

class YmlFileHandlerImpl : YmlFileHandler {
    override fun readFrom(filePath: String): Map<String, Any> {
        val yaml = Yaml()
        return File(filePath).inputStream().use { inputStream ->
            yaml.load(inputStream)
        }
    }
}