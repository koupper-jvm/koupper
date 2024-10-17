package com.koupper.providers.files

import org.yaml.snakeyaml.Yaml

import java.io.File

class YmlFileHandlerImpl : YmlFileHandler {
    override fun readFrom(filePath: String): Map<String, Any> {
        val yaml = Yaml()
        val inputStream = File(filePath).inputStream()
        val data = yaml.load<Map<String, Any>>(inputStream)
        return data
    }
}