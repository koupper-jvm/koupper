package com.koupper.octopus.api

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HttpApiServerTest : AnnotationSpec() {

    private val client = HttpClient.newHttpClient()
    private val baseUrl = "http://127.0.0.1:9997/api/v1"

    @BeforeTest
    fun setup() {
        HttpApiServer.stop()
    }

    @AfterTest
    fun teardown() {
        HttpApiServer.stop()
    }

    private fun createScriptExecutor(): com.koupper.octopus.ScriptExecutor {
        return object : com.koupper.octopus.ScriptExecutor {
            override fun <T> runFromScriptFile(context: String, scriptPath: String, params: String, result: (value: T) -> Unit) {
                @Suppress("UNCHECKED_CAST")
                result("mock-result" as T)
            }
            override fun <T> run(context: String, scriptPath: String?, sentence: String, params: com.koupper.octopus.ParsedParams?, callable: com.koupper.octopus.Callable?, result: (value: T) -> Unit) {
                @Suppress("UNCHECKED_CAST")
                result("mock-result" as T)
            }
            override fun <O> call(callable: kotlin.reflect.KProperty0<*>, vararg args: Any?): O {
                @Suppress("UNCHECKED_CAST")
                return "mock" as O
            }
            override fun <T> runScriptFiles(context: String, scripts: MutableMap<String, Map<String, Any>>, result: (value: T, scriptName: String) -> Unit) {
            }
            override fun <T> runFromCallback(callable: com.koupper.octopus.Callable, koTask: com.koupper.orchestrator.KouTask, result: (value: T) -> Unit) {
            }
            override fun <T> runFromUrl(context: String, scriptUrl: String, params: String, result: (value: T) -> Unit) {
            }
        }
    }

    @Test
    fun `health endpoint should return ok when server is running`() {
        HttpApiServer.start(createScriptExecutor(), port = 9997)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/health"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "Health endpoint should return 200")
        assertTrue(response.body().contains("ok"), "Health body should contain 'ok'")
    }

    @Test
    fun `run endpoint should require POST method`() {
        HttpApiServer.start(createScriptExecutor(), port = 9997)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/run"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(405, response.statusCode(), "GET to /run should return 405")
    }

    @Test
    fun `status endpoint should return 401 without auth when JWT is enabled`() {
        System.setProperty("koupper.octopus.jwt.secret", "test-secret")

        HttpApiServer.start(createScriptExecutor(), port = 9997)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/status"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, response.statusCode(), "Status without auth should return 401 when JWT enabled")

        System.clearProperty("koupper.octopus.jwt.secret")
    }

    @Test
    fun `status endpoint should return 200 with valid JWT`() {
        System.setProperty("koupper.octopus.jwt.secret", "test-secret")

        HttpApiServer.start(createScriptExecutor(), port = 9997)

        val token = com.koupper.octopus.security.JwtAuth.generateToken("test", listOf("koupper:admin"))
        assertNotNull(token, "Token should be generated")

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/status"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "Status with valid JWT should return 200")
        assertTrue(response.body().contains("uptimeMs"), "Status should contain metrics")

        System.clearProperty("koupper.octopus.jwt.secret")
    }
}
