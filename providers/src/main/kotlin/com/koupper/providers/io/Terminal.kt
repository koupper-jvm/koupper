package com.koupper.providers.io

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.UUID

class TerminalIO(
    private val reader: BufferedReader,
    private val writer: BufferedWriter
) {

    @Synchronized
    fun prompt(message: String, onResponse: (String) -> Unit) {
        val id = UUID.randomUUID().toString()

        // Enviar evento al CLI
        writer.write("PROMPT::$id::$message")
        writer.newLine()
        writer.flush()

        // Esperar respuesta del CLI
        while (true) {
            val line = reader.readLine() ?: break

            if (line.startsWith("PROMPT_RESPONSE::$id::")) {
                val response = line.substringAfter("PROMPT_RESPONSE::$id::")
                onResponse(response)
                break
            }
        }
    }

    @Synchronized
    fun print(message: String) {
        writer.write("PRINT::$message")
        writer.newLine()
        writer.flush()
    }
}
