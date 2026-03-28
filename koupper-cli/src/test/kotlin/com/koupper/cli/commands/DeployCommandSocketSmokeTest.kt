package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke-tests for DeployCommand that spin up an in-process ServerSocket (port=0 / OS-assigned)
 * and verify the CLI correctly builds, sends, and interprets DEPLOY socket messages.
 *
 * These tests do NOT start a real Octopus daemon — they only test the CLI's socket logic.
 */
class DeployCommandSocketSmokeTest {

    private val mapper = jacksonObjectMapper()

    @AfterTest
    fun cleanupSocketOverrides() {
        System.clearProperty("koupper.octopus.host")
        System.clearProperty("koupper.octopus.port")
        System.clearProperty("koupper.octopus.token")
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private fun buildTempScript(
        name: String = "worker.kts",
        content: String = "@Export\nval fn: () -> Unit = { println(\"ok\") }"
    ): File {
        val tempDir = Files.createTempDirectory("koupper-deploy-smoke").toFile().also { it.deleteOnExit() }
        return File(tempDir, name).also { it.writeText(content); it.deleteOnExit() }
    }

    // ─── test 1: happy path ───────────────────────────────────────────────────────

    @Test
    fun `deploy command should send DEPLOY type with scriptContent and return success`() {
        val scriptFile = buildTempScript()

        ServerSocket(0).use { serverSocket ->
            System.setProperty("koupper.octopus.host", "127.0.0.1")
            System.setProperty("koupper.octopus.port", serverSocket.localPort.toString())
            System.setProperty("koupper.octopus.token", "secret-token")

            var receivedType: String? = null
            var receivedContent: String? = null
            var receivedSha256: String? = null

            thread(start = true, isDaemon = true) {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    assertEquals("AUTH::secret-token", reader.readLine())

                    val requestJson = mapper.readTree(reader.readLine())
                    val realRequestId = requestJson["requestId"].asText()
                    receivedType = requestJson["type"]?.asText()
                    receivedContent = requestJson["scriptContent"]?.asText()
                    receivedSha256 = requestJson["contentSha256"]?.asText()

                    writer.write("""{"type":"result","requestId":"$realRequestId","result":"worker-deployed-ok"}""")
                    writer.newLine()
                    writer.flush()
                }
            }

            val command = DeployCommand()
            val destination = "127.0.0.1:${serverSocket.localPort}"
            val result = command.execute(scriptFile.parent, scriptFile.name, destination).trim()

            assertEquals("DEPLOY", receivedType, "CLI must send type=DEPLOY")
            assertTrue(
                receivedContent?.contains("@Export") == true,
                "CLI must send the script source in scriptContent"
            )
            assertTrue(!receivedSha256.isNullOrBlank(), "CLI must send contentSha256")
            assertTrue(result.contains("✅ Deployed"), "Result should indicate success")
            assertTrue(result.contains("worker-deployed-ok"), "Result should contain daemon reply")
        }
    }

    // ─── test 2: error path ───────────────────────────────────────────────────────

    @Test
    fun `deploy command should surface error from daemon`() {
        val scriptFile = buildTempScript()

        ServerSocket(0).use { serverSocket ->
            System.setProperty("koupper.octopus.host", "127.0.0.1")
            System.setProperty("koupper.octopus.port", serverSocket.localPort.toString())
            System.setProperty("koupper.octopus.token", "secret-token")

            thread(start = true, isDaemon = true) {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    assertEquals("AUTH::secret-token", reader.readLine())

                    val requestJson = mapper.readTree(reader.readLine())
                    val realRequestId = requestJson["requestId"].asText()

                    writer.write("""{"type":"error","requestId":"$realRequestId","error":"Script compilation failed"}""")
                    writer.newLine()
                    writer.flush()
                }
            }

            val command = DeployCommand()
            val destination = "127.0.0.1:${serverSocket.localPort}"
            val result = command.execute(scriptFile.parent, scriptFile.name, destination).trim()

            assertTrue(result.contains("❌ Deploy error"), "Result should indicate error")
            assertTrue(result.contains("Script compilation failed"), "Result should include error message")
        }
    }

    // ─── test 3: requestId correlation ────────────────────────────────────────────

    @Test
    fun `deploy command should ignore mismatched requestId and use matching response`() {
        val scriptFile = buildTempScript()

        ServerSocket(0).use { serverSocket ->
            System.setProperty("koupper.octopus.host", "127.0.0.1")
            System.setProperty("koupper.octopus.port", serverSocket.localPort.toString())
            System.setProperty("koupper.octopus.token", "secret-token")

            thread(start = true, isDaemon = true) {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                    assertEquals("AUTH::secret-token", reader.readLine())

                    val requestJson = mapper.readTree(reader.readLine())
                    val realRequestId = requestJson["requestId"].asText()

                    // First: wrong requestId — should be ignored
                    writer.write("""{"type":"result","requestId":"wrong-id-000","result":"ignore-me"}""")
                    writer.newLine()
                    // Second: correct requestId
                    writer.write("""{"type":"result","requestId":"$realRequestId","result":"matched-ok"}""")
                    writer.newLine()
                    writer.flush()
                }
            }

            val command = DeployCommand()
            val destination = "127.0.0.1:${serverSocket.localPort}"
            val result = command.execute(scriptFile.parent, scriptFile.name, destination).trim()

            assertTrue(
                result.contains("matched-ok"),
                "Should return the response matching the correct requestId, got: $result"
            )
        }
    }

    // ─── test 4: missing script file ─────────────────────────────────────────────

    @Test
    fun `deploy command should return error if local script does not exist`() {
        System.setProperty("koupper.octopus.token", "secret-token")
        val command = DeployCommand()
        val result = command.execute("/tmp", "non-existent.kts", "127.0.0.1").trim()
        assertTrue(result.contains("not found locally"), "Should report missing file, got: $result")
    }

    @Test
    fun `deploy command should require auth token before socket call`() {
        val scriptFile = buildTempScript(name = "token-required.kts")
        val command = DeployCommand()
        val result = command.execute(scriptFile.parent, scriptFile.name, "127.0.0.1").trim()
        assertTrue(result.contains("KOUPPER_OCTOPUS_TOKEN"), "Should request token configuration, got: $result")
    }

    // ─── test 5: destination parsing ─────────────────────────────────────────────

    @Test
    fun `parseDestination handles all supported formats`() {
        val cmd = DeployCommand()

        assertEquals("10.0.0.1" to 9998, cmd.parseDestination("10.0.0.1"))
        assertEquals("10.0.0.1" to 9999, cmd.parseDestination("10.0.0.1:9999"))
        assertEquals("10.0.0.1" to 9998, cmd.parseDestination("user@10.0.0.1"))
        assertEquals("10.0.0.1" to 9999, cmd.parseDestination("user@10.0.0.1:9999"))
    }
}
