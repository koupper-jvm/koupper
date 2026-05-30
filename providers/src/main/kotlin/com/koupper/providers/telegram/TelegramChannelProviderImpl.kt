package com.koupper.providers.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class TelegramChannelProviderImpl : TelegramChannelProvider {

    private val mapper = jacksonObjectMapper()

    // Java's HttpClient with a 35s timeout to allow 30s long-poll + overhead
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun baseUrl(token: String) = "https://api.telegram.org/bot$token"

    override fun startPolling(
        token: String,
        allowedChats: Set<Long>,
        running: () -> Boolean,
        onMessage: (chatId: Long, text: String) -> Unit
    ) {
        var offset = 0L

        while (running()) {
            try {
                val url = "${baseUrl(token)}/getUpdates?offset=$offset&timeout=30&allowed_updates=[\"message\"]"
                val req  = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(35))
                    .GET()
                    .build()

                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 200) { Thread.sleep(5000); continue }

                val body = mapper.readValue<Map<String, Any>>(resp.body())
                if (body["ok"] != true) { Thread.sleep(5000); continue }

                @Suppress("UNCHECKED_CAST")
                val updates = body["result"] as? List<Map<String, Any>> ?: continue

                for (update in updates) {
                    val updateId = (update["update_id"] as? Number)?.toLong() ?: continue
                    offset = updateId + 1

                    @Suppress("UNCHECKED_CAST")
                    val message  = update["message"] as? Map<String, Any> ?: continue
                    val text     = message["text"] as? String ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val chat     = message["chat"] as? Map<String, Any> ?: continue
                    val chatId   = (chat["id"] as? Number)?.toLong() ?: continue

                    if (allowedChats.isNotEmpty() && chatId !in allowedChats) continue

                    runCatching { onMessage(chatId, text) }
                }

            } catch (_: java.net.http.HttpTimeoutException) {
                // normal long-poll timeout — just loop
            } catch (e: Exception) {
                Thread.sleep(5000)
            }
        }
    }

    override fun sendMessage(token: String, chatId: Long, text: String) {
        if (text.isBlank()) return
        runCatching {
            val payload = mapper.writeValueAsString(mapOf(
                "chat_id"    to chatId,
                "text"       to text.take(4096),
                "parse_mode" to "HTML"
            ))
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl(token)}/sendMessage"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString())
        }
    }
}
