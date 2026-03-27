package com.koupper.cli.commands

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import java.io.File
import java.net.Socket

val isSingleFileName: (String) -> Boolean = {
    it.contains("^[a-zA-Z0-9]+.kts$".toRegex())
}

class RunCommand : Command() {
    init {
        super.name = "run"
        super.usage = "\n   koupper ${ANSI_GREEN_155}$name${ANSI_RESET} ${ANSI_GREEN_155}script-name.kts${ANSI_RESET}\n"
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

            return if (executionArgs.isNotEmpty()) {
                execute(context = context, args[1], args.drop(2).joinToString(" "))
            } else {
                execute(context = context, args[1])
            }
        }

        val initFile = args[0] + File.separator + "init.kts"

        return if (!File(initFile).exists()) {
            return "\n ${ANSI_WHITE}'init.kts' file not found. Create one using: ${ANSI_YELLOW_229}koupper new file:init${ANSI_WHITE} or start writing a script.\n"
        } else {
            execute(context, "init.kts")
        }
    }

    private fun execute(context: String, filePath: String, params: String = "EMPTY_PARAMS"): String {

        val file = if (File(filePath).isAbsolute) {
            File(filePath)
        } else {
            File(context + File.separator + filePath)
        }

        if (!file.exists()) {
            return "\n${ANSI_YELLOW_229} The script ${file.name} does not exist.${ANSI_RESET}\n"
        }

        return sendToOctopus(context, filePath, params)
    }

    private fun sendToOctopus(context: String, script: String, params: String): String {
        return try {
            Socket("localhost", 9998).use { socket ->
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                writer.write("\"$context\" \"$script\" $params")
                writer.newLine()
                writer.flush()

                println()

                val resultBuf = StringBuilder()
                var inResult = false

                var resultReceived = false

                while (true) {
                    val line = reader.readLine() ?: break

                    when {
                        line == "RESULT_BEGIN" -> {
                            inResult = true
                            resultBuf.clear()
                        }

                        line == "RESULT_END" -> {
                            resultReceived = true
                        }

                        inResult && !resultReceived -> {
                            resultBuf.appendLine(line)
                        }

                        line.startsWith("PRINT::") -> {
                            println(line.removePrefix("PRINT::"))
                        }

                        line.startsWith("PROMPT::") -> {
                            val parts = line.split("::", limit = 3)
                            val id = parts.getOrNull(1).orEmpty()
                            val msg = parts.getOrNull(2).orEmpty()

                            print("$msg ")
                            val input = readLine().orEmpty()

                            writer.write("PROMPT_RESPONSE::$id::$input")
                            writer.newLine()
                            writer.flush()
                        }

                        line.startsWith("ERROR::") -> {
                            return line.removePrefix("ERROR::")
                        }
                    }
                }

                return if (resultReceived) resultBuf.toString() else "Error: connection closed abruptly"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun name(): String {
        return AvailableCommands.RUN
    }

    override fun showArguments(): String {
        return ""
    }
}
