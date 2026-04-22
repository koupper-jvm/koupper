package com.koupper.octopus

import com.koupper.container.app
import com.koupper.logging.LogLevel
import com.koupper.logging.LoggerCore
import com.koupper.logging.LoggerFactory
import com.koupper.orchestrator.KouTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctopusSocketIntegrationTest {

    @AfterTest
    fun cleanupRuntimeSocketOverrides() {
        System.clearProperty("koupper.octopus.port")
        System.clearProperty("koupper.octopus.host")
        System.clearProperty("koupper.octopus.token")
        System.clearProperty("koupper.octopus.deploy.maxBytes")
    }

    @Test
    fun `legacy command should return legacy result envelope`() = runBlocking {
        withTestServer(maxConnections = 1) { port ->
            val lineList = sendRawCommand(port, listOf("\".\" \"test.kts\" hello"))

            assertTrue(lineList.firstOrNull() == "RESULT_BEGIN")
            assertContains(lineList.joinToString("\n"), "legacy-result:hello")
            assertTrue(lineList.lastOrNull() == "RESULT_END")
        }
    }

    @Test
    fun `json command should return json result with same requestId`() = runBlocking {
        withTestServer(maxConnections = 1) { port ->
            val requestId = UUID.randomUUID().toString()
            val response = sendRawCommand(
                port,
                listOf(
                    """{"type":"RUN","requestId":"$requestId","context":".","script":"test.kts","params":"json-ok"}"""
                )
            )

            val payload = response.joinToString("\n")
            assertContains(payload, "\"type\":\"result\"")
            assertContains(payload, "\"requestId\":\"$requestId\"")
            assertContains(payload, "json-result:json-ok")
        }
    }

    @Test
    fun `missing auth token should be rejected when token is required`() = runBlocking {
        withTestServer(maxConnections = 1, authToken = "secret-token") { port ->
            val response = sendRawCommand(port, listOf("\".\" \"test.kts\" blocked"))
            assertContains(response.joinToString("\n"), "ERROR::Unauthorized")
        }
    }

    @Test
    fun `json response requestId allows mismatch filtering on client side`() = runBlocking {
        withTestServer(maxConnections = 1) { port ->
            val expectedRequestId = "req-expected"
            val response = sendRawCommand(
                port,
                listOf(
                    """{"type":"RUN","requestId":"$expectedRequestId","context":".","script":"test.kts","params":"rid"}"""
                )
            )

            val payload = response.joinToString("\n")
            assertContains(payload, "\"requestId\":\"$expectedRequestId\"")
            assertTrue(!payload.contains("\"requestId\":\"req-other\""))
        }
    }

    @Test
    fun `deploy command should execute when auth and payload hash are valid`() = runBlocking {
        withTestServer(maxConnections = 1, authToken = "secret-token") { port ->
            val requestId = UUID.randomUUID().toString()
            val content = "@Export val run = 1"
            val hash = sha256Hex(content.toByteArray(Charsets.UTF_8))
            val response = sendRawCommand(
                port,
                listOf(
                    "AUTH::secret-token",
                    """{"type":"DEPLOY","requestId":"$requestId","script":"worker.kts","scriptContent":"$content","contentSha256":"$hash","params":"deploy-ok"}"""
                )
            )

            val payload = response.joinToString("\n")
            assertContains(payload, "\"type\":\"result\"")
            assertContains(payload, "\"requestId\":\"$requestId\"")
            assertContains(payload, "legacy-result:deploy-ok")
        }
    }

    @Test
    fun `deploy command should reject payload hash mismatch`() = runBlocking {
        withTestServer(maxConnections = 1, authToken = "secret-token") { port ->
            val requestId = UUID.randomUUID().toString()
            val content = "@Export val run = 1"
            val response = sendRawCommand(
                port,
                listOf(
                    "AUTH::secret-token",
                    """{"type":"DEPLOY","requestId":"$requestId","script":"worker.kts","scriptContent":"$content","contentSha256":"bad-hash","params":"deploy-fail"}"""
                )
            )

            assertContains(response.joinToString("\n"), "DEPLOY payload hash mismatch")
        }
    }

    @Test
    fun `deploy command should reject payload above max bytes`() = runBlocking {
        System.setProperty("koupper.octopus.deploy.maxBytes", "32")

        withTestServer(maxConnections = 1, authToken = "secret-token") { port ->
            val requestId = UUID.randomUUID().toString()
            val content = "@Export val payload = ${"9".repeat(200)}"
            val hash = sha256Hex(content.toByteArray(Charsets.UTF_8))
            val response = sendRawCommand(
                port,
                listOf(
                    "AUTH::secret-token",
                    """{"type":"DEPLOY","requestId":"$requestId","script":"worker.kts","scriptContent":"$content","contentSha256":"$hash","params":"overflow"}"""
                )
            )

            assertContains(response.joinToString("\n"), "DEPLOY payload exceeds max size")
        }
    }

    @Test
    fun `deploy command should be rejected when daemon auth token is not configured`() = runBlocking {
        withTestServer(maxConnections = 1, authToken = null) { port ->
            val requestId = UUID.randomUUID().toString()
            val content = "@Export val run = 1"
            val hash = sha256Hex(content.toByteArray(Charsets.UTF_8))
            val response = sendRawCommand(
                port,
                listOf(
                    """{"type":"DEPLOY","requestId":"$requestId","script":"worker.kts","scriptContent":"$content","contentSha256":"$hash","params":"unauth-deploy"}"""
                )
            )

            assertContains(response.joinToString("\n"), "DEPLOY requires daemon auth token configuration")
        }
    }

    private suspend fun withTestServer(
        maxConnections: Int,
        authToken: String? = null,
        block: suspend (port: Int) -> Unit
    ) {
        val port = reserveRandomPort()
        System.setProperty("koupper.octopus.port", port.toString())
        System.setProperty("koupper.octopus.host", "127.0.0.1")

        if (authToken != null) {
            System.setProperty("koupper.octopus.token", authToken)
        } else {
            System.clearProperty("koupper.octopus.token")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        runCatching {
            val logger = LoggerFactory.get("Octopus.Test")
            logger.level = LogLevel.ERROR
            app.singleton(LoggerCore::class, { logger })
        }

        val serverJob = scope.launch {
            listenForExternalCommands(FakeScriptExecutor(), scope, maxConnections)
        }

        delay(150)

        try {
            block(port)
        } finally {
            serverJob.cancel()
            scope.cancel()
        }
    }

    private fun sendRawCommand(port: Int, lines: List<String>): List<String> {
        var lastError: Exception? = null
        var socket: Socket? = null

        for (i in 0 until 20) {
            try {
                socket = Socket("127.0.0.1", port)
                break
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(50)
            }
        }

        val activeSocket = socket ?: throw IllegalStateException("Could not connect to test socket server", lastError)

        activeSocket.use { openedSocket ->
            openedSocket.soTimeout = 4000
            val writer = openedSocket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val reader = openedSocket.getInputStream().bufferedReader(Charsets.UTF_8)

            lines.forEach {
                writer.write(it)
                writer.newLine()
            }
            writer.flush()

            val output = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                output += line

                if (line == "RESULT_END") break
                if (line.startsWith("ERROR::")) break
                if (line.startsWith("{") && (line.contains("\"type\":\"result\"") || line.contains("\"type\":\"error\""))) break
            }

            return output
        }
    }

    private fun reserveRandomPort(): Int = ServerSocket(0).use { it.localPort }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class FakeScriptExecutor : ScriptExecutor {
        override fun <T> runFromScriptFile(context: String, scriptPath: String, params: String, result: (value: T) -> Unit) {
            val prefix = if (params == "json-ok" || params == "rid") "json-result" else "legacy-result"
            @Suppress("UNCHECKED_CAST")
            result("$prefix:$params" as T)
        }

        override fun <T> runFromCallback(callable: Callable, koTask: KouTask, result: (value: T) -> Unit) {
            throw UnsupportedOperationException("Not required for socket integration test")
        }

        override fun <T> runFromUrl(context: String, scriptUrl: String, params: String, result: (value: T) -> Unit) {
            throw UnsupportedOperationException("Not required for socket integration test")
        }

        override fun <T> runScriptFiles(context: String, scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit) {
            throw UnsupportedOperationException("Not required for socket integration test")
        }

        override fun <T> run(context: String, scriptPath: String?, sentence: String, params: ParsedParams?, callable: Callable?, result: (value: T) -> Unit) {
            throw UnsupportedOperationException("Not required for socket integration test")
        }

        override fun <O> call(callable: kotlin.reflect.KProperty0<*>, vararg args: Any?): O {
            throw UnsupportedOperationException("Not required for socket integration test")
        }
    }
}
