package com.koupper.providers.lsp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.PipedInputStream
import java.io.PipedOutputStream

class LspRpcTest : StringSpec({

    val mapper = ObjectMapper().registerKotlinModule()

    fun pair(): Pair<LspRpc, LspRpc> {
        // client → server
        val c2sIn  = PipedInputStream();  val c2sOut = PipedOutputStream(c2sIn)
        // server → client
        val s2cIn  = PipedInputStream();  val s2cOut = PipedOutputStream(s2cIn)
        val client = LspRpc(s2cIn, c2sOut, mapper)
        val server = LspRpc(c2sIn, s2cOut, mapper)
        return client to server
    }

    // ── Framing round-trip ────────────────────────────────────────────────────

    "send + receive round-trips a simple message" {
        val (client, server) = pair()
        val msg = mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize")
        client.send(msg)
        val received = server.receive()
        received shouldNotBe null
        received!!["method"] shouldBe "initialize"
        received["id"] shouldBe 1
    }

    "send + receive preserves nested objects" {
        val (client, server) = pair()
        val msg = mapOf(
            "jsonrpc" to "2.0",
            "method"  to "textDocument/didOpen",
            "params"  to mapOf(
                "textDocument" to mapOf(
                    "uri"  to "file:///src/Main.kt",
                    "text" to "fun main() {}"
                )
            )
        )
        client.send(msg)
        val received = server.receive()!!
        @Suppress("UNCHECKED_CAST")
        val td = (received["params"] as Map<*, *>)["textDocument"] as Map<*, *>
        td["uri"] shouldBe "file:///src/Main.kt"
        td["text"] shouldBe "fun main() {}"
    }

    "send + receive handles Unicode content correctly" {
        val (client, server) = pair()
        val unicode = "val x = \"Héllo wörld 日本語\""
        client.send(mapOf("method" to "test", "params" to mapOf("text" to unicode)))
        val received = server.receive()!!
        @Suppress("UNCHECKED_CAST")
        (received["params"] as Map<*, *>)["text"] shouldBe unicode
    }

    "multiple messages are received in order" {
        val (client, server) = pair()
        repeat(5) { i -> client.send(mapOf("id" to i, "method" to "ping")) }
        repeat(5) { i ->
            val msg = server.receive()!!
            msg["id"] shouldBe i
        }
    }

    "receive returns null on EOF" {
        val input  = "".toByteArray().inputStream()
        val output = java.io.ByteArrayOutputStream()
        val rpc    = LspRpc(input, output, mapper)
        rpc.receive() shouldBe null
    }
})
