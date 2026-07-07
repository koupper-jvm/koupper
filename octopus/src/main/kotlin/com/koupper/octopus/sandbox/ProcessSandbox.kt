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

        // Params travel via temp file: passing JSON as a process argument breaks on
        // Windows, where ProcessBuilder quoting corrupts the embedded escaped quotes.
        val paramsFile = File.createTempFile("koupper_sandbox_params_", ".json")
        paramsFile.writeText(jacksonObjectMapper().writeValueAsString(paramsJson))

        // Classpath travels in a java @argfile: Windows caps command lines at ~32K
        // chars and an inline -cp can exceed it (CreateProcess error=206).
        val classpathFile = File.createTempFile("koupper_sandbox_cp_", ".txt")
        classpathFile.writeText("-cp \"" + classpath.replace('\\', '/') + "\"")

        // The worker writes the script result here; stdout carries only live logs.
        val resultFile = File.createTempFile("koupper_sandbox_result_", ".txt")

        // The worker is a fresh JVM: without forwarding koupper.* properties it would
        // lose config like koupper.scripting.timeoutMs and never enforce script timeouts.
        val koupperProps = System.getProperties().stringPropertyNames()
            .filter { it.startsWith("koupper.") && it != "koupper.sandbox.enabled" }
            .map { "-D$it=${System.getProperty(it)}" }

        val command = mutableListOf(javaBin)
        command.addAll(koupperProps)
        command.add("@" + classpathFile.absolutePath)
        command.add("com.koupper.octopus.sandbox.SandboxWorkerKt")
        command.add(scriptFile.absolutePath)
        command.add(diParams.scriptContext)
        command.add(paramsFile.absolutePath)
        command.add(resultFile.absolutePath)

        val builder = ProcessBuilder(command)

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

            val timeoutMs = System.getProperty("koupper.scripting.timeoutMs")?.toLongOrNull()
                ?: TimeUnit.MINUTES.toMillis(5)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            // Destroy before join: the reader thread only ends when the process
            // closes its stdout, so joining a live process would hang forever.
            if (!finished) {
                process.destroyForcibly()
            }
            readerThread.join()

            val output = outputBuilder.toString()
            if (!finished) {
                throw RuntimeException("Sandbox execution timeout: el script excedió ${timeoutMs}ms.")
            }

            if (process.exitValue() != 0) {
                throw RuntimeException("Sandbox Execution Failed:\n$output")
            }

            // The result file is pre-created, so it always exists; a blank file means
            // the worker finished without invoking the result callback (e.g. terminal
            // resolvers like @Pipeline) — fall back to stdout as before.
            val resultText = if (resultFile.exists()) resultFile.readText().trim() else ""
            return if (resultText.isNotEmpty()) resultText else output.trim()
        } finally {
            paramsFile.delete()
            classpathFile.delete()
            resultFile.delete()
            if (isTempFile) {
                scriptFile?.delete()
            }
        }
    }
}
