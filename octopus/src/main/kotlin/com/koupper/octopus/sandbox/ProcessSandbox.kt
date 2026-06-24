package com.koupper.octopus.sandbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.octopus.DispatcherInputParams
import java.io.File
import java.util.concurrent.TimeUnit

object ProcessSandbox {
    fun execute(diParams: DispatcherInputParams, paramsJson: Map<String, Any?>): Any? {
        val javaHome = System.getProperty("java.home")
        val javaBin = javaHome + File.separator + "bin" + File.separator + "java"
        val classpath = System.getProperty("java.class.path")

        var scriptFile = if (diParams.scriptPath != null) File(diParams.scriptPath) else null
        var isTempFile = false

        if (scriptFile == null || !scriptFile.exists()) {
            scriptFile = File.createTempFile("koupper_sandbox_", ".kts")
            scriptFile.writeText(diParams.sentence)
            isTempFile = true
        }

        val paramsString = jacksonObjectMapper().writeValueAsString(paramsJson).replace("\"", "\\\"")

        val builder = ProcessBuilder(
            javaBin,
            "-cp", classpath,
            "com.koupper.octopus.sandbox.SandboxWorkerKt",
            scriptFile.absolutePath,
            diParams.scriptContext,
            paramsString
        )

        builder.redirectErrorStream(true)

        try {
            val process = builder.start()
            val outputBuilder = StringBuilder()

            val readerThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Esto será capturado por el SessionStdoutBridge del demonio principal
                        println(line)
                        outputBuilder.append(line).append("\n")
                    }
                }
            }
            readerThread.start()

            val finished = process.waitFor(5, TimeUnit.MINUTES)
            readerThread.join()

            val output = outputBuilder.toString()
            if (!finished) {
                process.destroyForcibly()
                throw RuntimeException("Sandbox Execution Timeout: El proceso tardó más de 5 minutos.")
            }

            if (process.exitValue() != 0) {
                throw RuntimeException("Sandbox Execution Failed:\n$output")
            }

            return output.trim()
        } finally {
            if (isTempFile) {
                scriptFile?.delete()
            }
        }
    }
}
