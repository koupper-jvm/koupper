package com.koupper.cli.deserializer

import java.io.File

fun createDeserializer(context: String) {
    val projectName = File(context).name
    val deserializerPackage = "$projectName.deserializer"
    val deserializerPath = deserializerPackage.replace(".", "/")

    val targetKt = File("$context/src/main/kotlin/$deserializerPath/JobDeserializer.kt")
    targetKt.parentFile.mkdirs()
    targetKt.writeText("""
package $deserializerPackage

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object JobDeserializer {
    private val mapper = jacksonObjectMapper()

    inline fun <reified T> fromJson(json: String): T = mapper.readValue(json)
    fun <T> toJson(value: T): String = mapper.writeValueAsString(value)
}
""".trimIndent())
}
