package com.koupper.providers.agent

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sidecar Sincrónico: Sin corrutinas para evitar deadlocks en el motor de scripts.
 */
class LlamaCppSidecar(
    private val budget: AgentBudget,
    private val modelPath: String,
    private val executablePath: String = "llama-cli"
) {

    /**
     * Ejecución bloqueante y segura.
     */
    fun inferSync(prompt: String): String {
        val threads = budget.telemetry.physicalCores
        
        // Comando ultra-agresivo para evitar interactividad
        val command = listOf(
            "timeout", "30s",
            executablePath,
            "-m", modelPath,
            "-p", prompt,
            "-t", threads.toString(),
            "-ngl", "0",
            "--no-display-prompt",
            "-n", "64", // Solo 64 tokens para el test
            "--log-disable",
            "--simple-io"
        )

        return try {
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()

            val result = StringBuilder()
            process.inputStream.use { input ->
                val reader = input.bufferedReader()
                
                // LEEMOS CON LÍMITE FÍSICO PARA EVITAR OOM
                var char = reader.read()
                var count = 0
                while (char != -1 && count < 10000) { // Max 10KB de respuesta
                    result.append(char.toChar())
                    char = reader.read()
                    count++
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
            
            result.toString().trim()
        } catch (e: Exception) {
            "[ERROR_SIDECAR]: ${e.message}"
        }
    }
}
