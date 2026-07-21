package com.koupper.providers.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OpenAICompatibleEngineTest : StringSpec({

    fun mockServer(vararg responses: MockResponse): MockWebServer {
        val server = MockWebServer()
        responses.forEach { server.enqueue(it) }
        server.start()
        return server
    }

    fun sseResponse(vararg tokens: String, includeRole: Boolean = false): MockResponse {
        val sb = StringBuilder()
        if (includeRole) {
            sb.append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"index\":0}]}\n\n")
        }
        tokens.forEach { token ->
            sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"$token\"},\"index\":0}]}\n\n")
        }
        sb.append("data: [DONE]\n\n")
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(sb.toString())
    }

    fun nonStreamResponse(content: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"role":"assistant","content":"$content"}}]}""")

    fun engine(server: MockWebServer, key: String = "test-key"): OpenAICompatibleEngine {
        val client = OkHttpClient()
        return OpenAICompatibleEngine(
            baseUrl = server.url("/v1").toString(),
            apiKey  = key,
            model   = "test-model",
            client  = client
        )
    }

    // ── Happy path — streaming ────────────────────────────────────────────────

    "predict should concatenate SSE tokens into full response" {
        val server = mockServer(sseResponse("Hello", " ", "World"))
        try {
            val sut = engine(server)
            val tokens = mutableListOf<String>()
            val listener = object : TokenListener {
                override fun onToken(token: String, agentId: String) { tokens.add(token) }
            }
            val result = runBlocking {
                sut.predict<String>(
                    history = listOf(AgentMessage("user", "hi")),
                    listener = listener
                )
            }
            result         shouldBe "Hello World"
            tokens         shouldBe listOf("Hello", " ", "World")
        } finally { server.shutdown() }
    }

    "predict should send Authorization header with Bearer token" {
        val server = mockServer(sseResponse("ok"))
        try {
            val sut = engine(server, key = "sk-secret-key")
            runBlocking {
                sut.predict<String>(listOf(AgentMessage("user", "hi")), listener = object : TokenListener {
                    override fun onToken(token: String, agentId: String) {}
                })
            }
            val request = server.takeRequest()
            request.getHeader("Authorization") shouldBe "Bearer sk-secret-key"
        } finally { server.shutdown() }
    }

    "predict should hit /chat/completions endpoint" {
        val server = mockServer(sseResponse("ok"))
        try {
            val sut = engine(server)
            runBlocking {
                sut.predict<String>(listOf(AgentMessage("user", "hi")), listener = object : TokenListener {
                    override fun onToken(token: String, agentId: String) {}
                })
            }
            server.takeRequest().path shouldContain "/chat/completions"
        } finally { server.shutdown() }
    }

    "predict should map history roles to messages array" {
        val server = mockServer(sseResponse("ok"))
        try {
            val sut = engine(server)
            val history = listOf(
                AgentMessage("system", "You are helpful."),
                AgentMessage("user", "Hello"),
                AgentMessage("assistant", "Hi there")
            )
            runBlocking {
                sut.predict<String>(history, listener = object : TokenListener {
                    override fun onToken(token: String, agentId: String) {}
                })
            }
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "\"role\":\"system\""
            body shouldContain "\"role\":\"user\""
            body shouldContain "\"role\":\"assistant\""
        } finally { server.shutdown() }
    }

    // ── Happy path — non-streaming ────────────────────────────────────────────

    "predict without listener should use non-streaming path" {
        val server = mockServer(nonStreamResponse("Non-stream response"))
        try {
            val sut = engine(server)
            val result = runBlocking {
                sut.predict<String>(listOf(AgentMessage("user", "hi")))
            }
            result shouldBe "Non-stream response"
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "\"stream\":false"
        } finally { server.shutdown() }
    }

    "predict with listener should set stream true in request body" {
        val server = mockServer(sseResponse("ok"))
        try {
            val sut = engine(server)
            runBlocking {
                sut.predict<String>(listOf(AgentMessage("user", "hi")), listener = object : TokenListener {
                    override fun onToken(token: String, agentId: String) {}
                })
            }
            val body = server.takeRequest().body.readUtf8()
            body shouldContain "\"stream\":true"
        } finally { server.shutdown() }
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    "predict should throw IllegalArgumentException when apiKey is blank" {
        val server = MockWebServer().also { it.start() }
        try {
            val sut = engine(server, key = "")
            shouldThrow<IllegalArgumentException> {
                runBlocking { sut.predict<String>(listOf(AgentMessage("user", "hi"))) }
            }
        } finally { server.shutdown() }
    }

    "predict should throw when server returns non-200 status" {
        val server = mockServer(
            MockResponse().setResponseCode(401).setBody("""{"error":"invalid_api_key"}""")
        )
        try {
            val sut = engine(server)
            shouldThrow<IllegalStateException> {
                runBlocking { sut.predict<String>(listOf(AgentMessage("user", "hi"))) }
            }
        } finally { server.shutdown() }
    }

    "predict should ignore SSE lines without content delta" {
        val server = mockServer(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"actual\"}}]}\n\n" +
                    "data: [DONE]\n\n"
                )
        )
        try {
            val sut = engine(server)
            val tokens = mutableListOf<String>()
            val result = runBlocking {
                sut.predict<String>(listOf(AgentMessage("user", "hi")), listener = object : TokenListener {
                    override fun onToken(token: String, agentId: String) { tokens.add(token) }
                })
            }
            result shouldBe "actual"
            tokens shouldBe listOf("actual")
        } finally { server.shutdown() }
    }
})
