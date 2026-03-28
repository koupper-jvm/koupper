package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RunCommandSocketSmokeTest {
    private val mapper = jacksonObjectMapper()

    @AfterTest
    fun cleanupSocketOverrides() {
        System.clearProperty("koupper.octopus.host")
        System.clearProperty("koupper.octopus.port")
        System.clearProperty("koupper.octopus.token")
    }

    @Test
    fun `run command should parse json response and return result`() {
        withSocketServer { serverSocket ->
            thread(start = true, isDaemon = true) {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val request = mapper.readTree(reader.readLine())
                    val realRequestId = request["requestId"].asText()

                    writer.write("{\"type\":\"print\",\"requestId\":\"$realRequestId\",\"message\":\"hello\"}")
                    writer.newLine()
                    writer.write("{\"type\":\"result\",\"requestId\":\"$realRequestId\",\"result\":\"json-ok\"}")
                    writer.newLine()
                    writer.flush()
                }
            }

            val output = executeRunCommandWithTempScript("demo.kts", "param=1")
            assertEquals("json-ok", output)
        }
    }

    @Test
    fun `run command should ignore mismatched requestId and use matching response`() {
        withSocketServer { serverSocket ->
            thread(start = true, isDaemon = true) {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    val request = mapper.readTree(reader.readLine())
                    val realRequestId = request["requestId"].asText()

                    writer.write("{\"type\":\"result\",\"requestId\":\"req-other\",\"result\":\"ignore-me\"}")
                    writer.newLine()
                    writer.write("{\"type\":\"result\",\"requestId\":\"$realRequestId\",\"result\":\"matched\"}")
                    writer.newLine()
                    writer.flush()
                }
            }

            val output = executeRunCommandWithTempScript("demo.kts")
            assertEquals("matched", output)
        }
    }

    @Test
    fun `run command should fallback to legacy result envelope`() {
        withSocketServer { serverSocket ->
            thread(start = true, isDaemon = true) {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    reader.readLine()
                    writer.write("RESULT_BEGIN")
                    writer.newLine()
                    writer.write("legacy-ok")
                    writer.newLine()
                    writer.write("RESULT_END")
                    writer.newLine()
                    writer.flush()
                }
            }

            val output = executeRunCommandWithTempScript("legacy.kts")
            assertContains(output, "legacy-ok")
        }
    }

    private fun withSocketServer(block: (ServerSocket) -> Unit) {
        ServerSocket(0).use { serverSocket ->
            System.setProperty("koupper.octopus.host", "127.0.0.1")
            System.setProperty("koupper.octopus.port", serverSocket.localPort.toString())
            block(serverSocket)
        }
    }

    private fun executeRunCommandWithTempScript(scriptName: String, params: String = "EMPTY_PARAMS"): String {
        val tempDir = Files.createTempDirectory("koupper-run-smoke").toFile()
        tempDir.deleteOnExit()

        val script = File(tempDir, scriptName)
        script.writeText("println(\"ok\")")
        script.deleteOnExit()

        val command = RunCommand()
        return command.execute(tempDir.absolutePath, scriptName, params).trim()
    }
}
