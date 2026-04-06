package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

class DeployCommand : Command() {
    private val mapper = jacksonObjectMapper()

    init {
        super.name = "deploy"
        super.usage = "\n   koupper ${ANSI_GREEN_155}deploy${ANSI_RESET} ${ANSI_GREEN_155}script.kts${ANSI_RESET} ${ANSI_GREEN_155}<host[:port]>${ANSI_RESET}\n"
        super.description = "\n   Deploy a local .kts script to a remote Octopus daemon\n"
        super.arguments = emptyMap()
        super.additionalInformation = """
   Examples:
     koupper deploy worker.kts 10.0.0.5
     koupper deploy worker.kts 10.0.0.5:9999
     koupper deploy worker.kts user@10.0.0.5

   Environment variables:
     KOUPPER_OCTOPUS_TOKEN  — auth token (must match the remote Octopus token)

   For more info: https://koupper.com/cli/commands/deploy
        """
    }

    override fun execute(vararg args: String): String {
        // args[0] = context / cwd
        // args[1] = local script file
        // args[2] = destination: host, host:port, or user@host
        if (args.size < 3) {
            return "\n${ANSI_YELLOW_229} Usage: koupper deploy script.kts <host[:port]>${ANSI_RESET}\n"
        }

        val scriptArg = args[1]
        if (".kts" !in scriptArg && ".kt" !in scriptArg) {
            return "\n${ANSI_YELLOW_229} The file must end with [.kts || .kt] extension.${ANSI_RESET}\n"
        }

        val localFile = if (File(scriptArg).isAbsolute) {
            File(scriptArg)
        } else {
            File(args[0], scriptArg)
        }

        if (!localFile.exists()) {
            return "\n${ANSI_YELLOW_229} Script '${localFile.name}' not found locally.${ANSI_RESET}\n"
        }

        val destination = args[2]
        val (remoteHost, remotePort) = parseDestination(destination)
        val token = runtimeOctopusToken()
        if (token.isNullOrBlank()) {
            return "❌ Deploy requires KOUPPER_OCTOPUS_TOKEN (or -Dkoupper.octopus.token)"
        }
        val params = if (args.size > 3) args.drop(3).joinToString(" ") else "EMPTY_PARAMS"
        val scriptContent = localFile.readText(Charsets.UTF_8)
        val contentSha256 = sha256Hex(scriptContent.toByteArray(Charsets.UTF_8))

        return sendDeployToOctopus(
            host = remoteHost,
            port = remotePort,
            token = token,
            scriptName = localFile.name,
            scriptContent = scriptContent,
            contentSha256 = contentSha256,
            params = params
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /**
     * Accepted destination formats:
     *   192.168.1.10           → host=192.168.1.10, port=9998
     *   192.168.1.10:9999      → host=192.168.1.10, port=9999
     *   user@192.168.1.10      → host=192.168.1.10, port=9998  (user part ignored; auth via token)
     *   user@192.168.1.10:9999 → host=192.168.1.10, port=9999
     */
    internal fun parseDestination(destination: String): Pair<String, Int> {
        val withoutUser = destination.substringAfter("@")
        return if (":" in withoutUser) {
            val parts = withoutUser.split(":", limit = 2)
            parts[0] to (parts[1].toIntOrNull() ?: 9998)
        } else {
            withoutUser to 9998
        }
    }

    private fun runtimeOctopusToken(): String? {
        val fromProperty = System.getProperty("koupper.octopus.token")?.trim()
        if (!fromProperty.isNullOrBlank()) return fromProperty
        val fromEnv = System.getenv("KOUPPER_OCTOPUS_TOKEN")?.trim()
        if (!fromEnv.isNullOrBlank()) return fromEnv
        return null
    }

    private fun sendDeployToOctopus(
        host: String,
        port: Int,
        token: String?,
        scriptName: String,
        scriptContent: String,
        contentSha256: String,
        params: String
    ): String {
        return try {
            Socket(host, port).use { socket ->
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestId = UUID.randomUUID().toString()

                // Auth (same as RunCommand)
                token?.let {
                    writer.write("AUTH::$it")
                    writer.newLine()
                }

                // DEPLOY payload — includes full script source
                writer.write(
                    mapper.writeValueAsString(
                        mapOf(
                            "type" to "DEPLOY",
                            "requestId" to requestId,
                            "script" to scriptName,
                            "scriptContent" to scriptContent,
                            "contentSha256" to contentSha256,
                            "params" to params
                        )
                    )
                )
                writer.newLine()
                writer.flush()

                println()

                // Response loop (mirrors RunCommand exactly)
                val resultBuf = StringBuilder()
                var inLegacyResult = false
                var resultReceived = false

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
                                    return "✅ Deployed: ${node.get("result")?.asText().orEmpty()}"
                                }

                                "error" -> {
                                    return "❌ Deploy error: ${node.get("error")?.asText() ?: "Unknown daemon error"}"
                                }
                            }

                            continue
                        }
                    }

                    when {
                        line == "RESULT_BEGIN" -> {
                            inLegacyResult = true
                            resultBuf.clear()
                        }

                        line == "RESULT_END" -> {
                            resultReceived = true
                        }

                        inLegacyResult && !resultReceived -> {
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

                        line.startsWith("ERROR::") -> {
                            return "❌ Deploy error: ${line.removePrefix("ERROR::")}"
                        }
                    }
                }

                return if (resultReceived) "✅ Deployed: ${resultBuf.toString().trim()}"
                else "❌ Deploy error: connection closed abruptly"
            }
        } catch (e: Exception) {
            "❌ Cannot connect to $host:$port — ${e.message}"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun name(): String = AvailableCommands.DEPLOY

    override fun showArguments(): String = ""
}
