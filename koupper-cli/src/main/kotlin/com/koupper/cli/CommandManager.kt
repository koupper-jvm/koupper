package com.koupper.cli

import com.koupper.cli.ANSIColors.ANSI_RED
import com.koupper.cli.commands.*
import com.koupper.cli.commands.AvailableCommands.HELP
import com.koupper.cli.commands.AvailableCommands.MODULE
import com.koupper.cli.commands.AvailableCommands.NEW
import com.koupper.cli.commands.AvailableCommands.RUN
import com.koupper.cli.commands.AvailableCommands.JOB
import com.koupper.cli.commands.jobs.JobCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val userPath = System.getProperty("user.home")

private fun checkForUpdatesFrom(baseDate: String) {
    try {
        val process = Runtime.getRuntime()
            .exec("$userPath/.koupper/helpers/octopusBootstrapper.sh UPDATING_CHECK")

        process.waitFor()

        val errors = process.errorStream.bufferedReader().readText()

        if (errors.isNotEmpty()) {
            print("$ANSI_RED$errors${ANSIColors.ANSI_RESET}")

            exitProcess(7)
        }

        val version = File("$userPath/.koupper/helpers/.update_info")
        version.createNewFile()

        val output = process.inputStream.bufferedReader().readText()
        val readyForUpdate = output == "AVAILABLE_UPDATES"

        version.printWriter()
            .use { out -> out.println("{\"last_request\": $baseDate, \"ready_for_update\": $readyForUpdate") }
    } catch (exception: IOException) {
        println("\n$ANSI_RED${exception.printStackTrace()}\n")
    }
}
class CommandManager {
    companion object {
        val commands: Map<String, Command> = mapOf(
            HELP to HelpCommand(),
            NEW to NewCommand(),
            RUN to RunCommand(),
            MODULE to ModuleCommand(),
            JOB to JobCommand()
        )
    }

    fun process(arg: Array<String>): String {
        if (this.isFlagVersion(arg[1])) {
            return DefaultCommand().showDescription()
        }

        val command = getCommandByName(arg[1])

        if (command is UndefinedCommand) {
            return command.execute(arg[1])
        }

        val args = this.getArgsFrom(arg)

        return command.execute(arg[0], *args)
    }

    fun notifyAboutUpdate(baseDate: String) {
        print("updates are available, Would you like apply them now? [y/n] ")

        val updateInfo = File("$userPath/.koupper/helpers/.update_info")

        when (readlnOrNull()) {
            "y", "Y" -> {
                val content = URL("https://lib-installer.s3.amazonaws.com/updateme.txt").readText()

                val file = File("$userPath/.koupper/helpers/updateme.kts")
                file.writeText(content)
                file.createNewFile()
                file.setExecutable(true)
                file.setReadable(true)
                file.setWritable(true)
                updateInfo.printWriter()
                    .use { out -> out.println("{\"last_request\": ${Date().time}, \"ready_for_update\": false}") }
            }

            else -> {
                val lastRequest = Date(baseDate.toLong() - (3600 * 3) * 1000).time

                updateInfo.printWriter()
                    .use { out -> out.println("{\"last_request\": $lastRequest, \"ready_for_update\": true}") }
            }
        }
    }

    fun getCommandByName(input: String): Command {
        return commands[input] ?: UndefinedCommand()
    }

    private fun isFlagVersion(input: String): Boolean {
        return input == "-v" || input == "--v" || input == "--version" || input == "-version"
    }

    private fun getArgsFrom(arg: Array<String>): Array<String> {
        return if (arg.size > 2) arg.sliceArray(2 until arg.size) else emptyArray()
    }
}

fun main(args: Array<String>) = runBlocking {
    val commandManager = CommandManager()

    val context = System.getProperty("user.dir")
    val input = arrayOf(context, *args)

    val response = if (input.size == 1) {
        DefaultCommand().execute()
    } else {
        commandManager.process(input)
    }

    print(response)
}

fun startSocketServer(commandManager: CommandManager, scope: CoroutineScope) {
    val serverSocket = ServerSocket(9999)
    println("🔄 Servidor de sockets iniciado en el puerto 9999...")

    while (true) {
        val clientSocket = serverSocket.accept()
        println("🔗 Nueva conexión a koupper-cli: ${clientSocket.inetAddress.hostAddress}")

        scope.launch {
            clientSocket.use { socket ->
                try {
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()

                    val command = reader.readLine()?.trim()

                    if (!command.isNullOrEmpty()) {
                        val input = command.split(" ").toTypedArray()

                        val response: String

                        if (input.size == 1) {
                            response = DefaultCommand().execute()
                        } else {
                            println("📥 Comando recibido en koupper-cli: $command")

                            response = commandManager.process(input)
                        }

                        println("📥 Response cli: $response")
                        writer.write(response)
                        writer.flush()
                    }
                } catch (e: Exception) {
                    println("⚠️ Error en la conexión: ${e.message}")
                }
            }
        }
    }
}
