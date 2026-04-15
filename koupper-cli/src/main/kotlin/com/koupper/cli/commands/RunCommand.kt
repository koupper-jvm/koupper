package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val OCTOPUS_HOST_PROPERTY = "koupper.octopus.host"
private const val OCTOPUS_HOST_ENV = "KOUPPER_OCTOPUS_HOST"
private const val OCTOPUS_PORT_PROPERTY = "koupper.octopus.port"
private const val OCTOPUS_PORT_ENV = "KOUPPER_OCTOPUS_PORT"

val isSingleFileName: (String) -> Boolean = {
    it.contains("^[a-zA-Z0-9]+.kts$".toRegex())
}

class RunCommand : Command() {
    private val mapper = jacksonObjectMapper()

    private fun runtimeOctopusHost(): String {
        val fromProperty = System.getProperty(OCTOPUS_HOST_PROPERTY)?.trim()
        if (!fromProperty.isNullOrBlank()) return fromProperty

        val fromEnv = System.getenv(OCTOPUS_HOST_ENV)?.trim()
        if (!fromEnv.isNullOrBlank()) return fromEnv

        return "localhost"
    }

    private fun runtimeOctopusPort(): Int {
        val fromProperty = System.getProperty(OCTOPUS_PORT_PROPERTY)?.trim()?.toIntOrNull()
        if (fromProperty != null) return fromProperty

        val fromEnv = System.getenv(OCTOPUS_PORT_ENV)?.trim()?.toIntOrNull()
        if (fromEnv != null) return fromEnv

        return 9998
    }

    private fun runtimeOctopusToken(): String? {
        val fromProperty = System.getProperty("koupper.octopus.token")?.trim()
        if (!fromProperty.isNullOrBlank()) return fromProperty

        val fromEnv = System.getenv("KOUPPER_OCTOPUS_TOKEN")?.trim()
        if (!fromEnv.isNullOrBlank()) return fromEnv

        return null
    }

    init {
        super.name = "run"
        super.usage = "\n   koupper ${ANSI_GREEN_155}$name${ANSI_RESET} ${ANSI_GREEN_155}script-name.kts${ANSI_RESET} [params | --json-file <file.json>] [--serve]\n"
        super.description = "\n   Run a kotlin script\n"
        super.arguments = emptyMap()
        super.additionalInformation = """
   For more info: https://koupper.com/cli/commands/run
        """
    }

    override fun execute(vararg args: String): String {
        val context = args[0]

        if (args.size > 1 && args[1].isNotEmpty()) {
            if (".kts" !in args[1] && ".kt" !in args[1]) {
                return "\n${ANSI_YELLOW_229} The file must end with [.kts || .kt] extension.${ANSI_RESET}\n"
            }

            val executionArgs = args.sliceArray(2 until args.size)
            val serveMode = executionArgs.contains("--serve")
            val sanitizedArgs = executionArgs.filterNot { it == "--serve" }.toTypedArray()

            if (sanitizedArgs.isNotEmpty() && sanitizedArgs[0] == "--json-file") {
                if (sanitizedArgs.size < 2) {
                    return "\n${ANSI_YELLOW_229} Missing JSON file path. Usage: koupper run <script> --json-file <file.json>.${ANSI_RESET}\n"
                }

                val jsonFilePath = sanitizedArgs[1]
                val jsonFile = if (File(jsonFilePath).isAbsolute) {
                    File(jsonFilePath)
                } else {
                    File(context + File.separator + jsonFilePath)
                }

                if (!jsonFile.exists()) {
                    return "\n${ANSI_YELLOW_229} JSON file ${jsonFile.name} does not exist.${ANSI_RESET}\n"
                }

                val jsonPayload = jsonFile.readText(Charsets.UTF_8).trim()
                if (jsonPayload.isBlank()) {
                    return "\n${ANSI_YELLOW_229} JSON file ${jsonFile.name} is empty.${ANSI_RESET}\n"
                }

                return execute(context = context, filePath = args[1], params = jsonPayload, serveMode = serveMode)
            }

            return if (sanitizedArgs.isNotEmpty()) {
                execute(context = context, args[1], sanitizedArgs.joinToString(" "), serveMode)
            } else {
                execute(context = context, args[1], serveMode = serveMode)
            }
        }

        val initFile = args[0] + File.separator + "init.kts"

        return if (!File(initFile).exists()) {
            return "\n ${ANSI_WHITE}'init.kts' file not found. Create one using: ${ANSI_YELLOW_229}koupper new file:init${ANSI_WHITE} or start writing a script.${ANSI_RESET}\n"
        } else {
            execute(context, "init.kts")
        }
    }

    private fun execute(context: String, filePath: String, params: String = "EMPTY_PARAMS", serveMode: Boolean = false): String {

        val file = if (File(filePath).isAbsolute) {
            File(filePath)
        } else {
            File(context + File.separator + filePath)
        }

        if (!file.exists()) {
            return "\n${ANSI_YELLOW_229} The script ${file.name} does not exist.${ANSI_RESET}\n"
        }

        val executionContext = if (file.isAbsolute) {
            file.parentFile?.absolutePath ?: context
        } else {
            context
        }

        return sendToOctopus(executionContext, file.path, params, serveMode)
    }

    private fun sendToOctopus(context: String, script: String, params: String, serveMode: Boolean): String {
        var cancellationHook: Thread? = null
        return try {
            val requestId = UUID.randomUUID().toString()
            val responseReceived = AtomicBoolean(false)

            cancellationHook = Thread {
                if (serveMode && !responseReceived.get()) {
                    runCatching { sendCancel(requestId) }
                }
            }

            Runtime.getRuntime().addShutdownHook(cancellationHook)

            Socket(runtimeOctopusHost(), runtimeOctopusPort()).use { socket ->
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)

                runtimeOctopusToken()?.let { token ->
                    writer.write("AUTH::$token")
                    writer.newLine()
                }

                writer.write(
                    mapper.writeValueAsString(
                        mapOf(
                                "type" to "RUN",
                                "requestId" to requestId,
                                "context" to context,
                                "script" to script,
                                "params" to params
                        )
                    )
                )
                writer.newLine()
                writer.flush()

                println()

                if (serveMode) {
                    println("[serve] Live mode enabled for '$script'. Press Ctrl+C to stop.")
                }

                val resultBuf = StringBuilder()
                var inResult = false

                var legacyResultReceived = false

                while (true) {
                    val line = reader.readLine() ?: break

                    if (line.startsWith("{")) {
                        val node = runCatching { mapper.readTree(line) }.getOrNull()
                        if (node != null && node.has("type")) {
                            val type = node.get("type")?.asText().orEmpty().lowercase()
                            val incomingRequestId = node.get("requestId")?.asText()

                            if (!incomingRequestId.isNullOrBlank() && incomingRequestId != requestId) {
                                continue
                            }

                            when (type) {
                                "print" -> {
                                    val message = node.get("message")?.asText().orEmpty()
                                    val level = node.get("level")?.asText().orEmpty().uppercase()
                                    if (message.isNotEmpty()) {
                                        if (level == "WARN" || level == "ERROR") {
                                            System.err.println(message)
                                        } else {
                                            println(message)
                                        }
                                    }
                                }

                                "result" -> {
                                    responseReceived.set(true)
                                    return node.get("result")?.asText() ?: ""
                                }

                                "error" -> {
                                    responseReceived.set(true)
                                    return node.get("error")?.asText() ?: "Unknown daemon error"
                                }
                            }

                            continue
                        }
                    }

                    when {
                        line == "RESULT_BEGIN" -> {
                            inResult = true
                            resultBuf.clear()
                        }

                        line == "RESULT_END" -> {
                            legacyResultReceived = true
                        }

                        inResult && !legacyResultReceived -> {
                            resultBuf.appendLine(line)
                        }

                        line.startsWith("PRINT::") -> {
                            println(line.removePrefix("PRINT::"))
                        }

                        line.startsWith("PRINT_DEBUG::") -> {
                            println(line.removePrefix("PRINT_DEBUG::"))
                        }

                        line.startsWith("PRINT_ERR::") -> {
                            System.err.println(line.removePrefix("PRINT_ERR::"))
                        }

                        line.startsWith("PROMPT::") -> {
                            val parts = line.split("::", limit = 3)
                            val id = parts.getOrNull(1).orEmpty()
                            val msg = parts.getOrNull(2).orEmpty()

                            println(msg)
                            print("> ")
                            System.out.flush()
                            val input = readLine().orEmpty()

                            writer.write("PROMPT_RESPONSE::$id::$input")
                            writer.newLine()
                            writer.flush()
                        }

                        line.startsWith("ERROR::") -> {
                            responseReceived.set(true)
                            return line.removePrefix("ERROR::")
                        }
                    }
                }

                responseReceived.set(true)
                return if (legacyResultReceived) resultBuf.toString() else "Error: connection closed abruptly"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            if (cancellationHook != null) {
                runCatching { Runtime.getRuntime().removeShutdownHook(cancellationHook) }
            }
        }
    }

    private fun sendCancel(requestId: String) {
        Socket(runtimeOctopusHost(), runtimeOctopusPort()).use { socket ->
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

            runtimeOctopusToken()?.let { token ->
                writer.write("AUTH::$token")
                writer.newLine()
            }

            writer.write(
                mapper.writeValueAsString(
                    mapOf(
                        "type" to "CANCEL",
                        "requestId" to requestId
                    )
                )
            )
            writer.newLine()
            writer.flush()
        }
    }

    override fun name(): String {
        return AvailableCommands.RUN
    }

    override fun showArguments(): String {
        return ""
    }
}
