package com.koupper.providers.notifications

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class NotificationResult(
    val ok: Boolean,
    val statusCode: Int,
    val provider: String,
    val responseBody: String
)

interface NotificationsProvider {
    fun sendText(channel: String, text: String): NotificationResult
    fun sendStructured(channel: String, payload: Map<String, Any?>): NotificationResult
    fun sendError(channel: String, title: String, details: String): NotificationResult
}

class WebhookNotificationsProvider(
    private val webhookUrl: String,
    private val providerName: String = "generic-webhook"
) : NotificationsProvider {
    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newHttpClient()

    override fun sendText(channel: String, text: String): NotificationResult {
        val payload = mapOf("channel" to channel, "text" to text)
        return post(payload)
    }

    override fun sendStructured(channel: String, payload: Map<String, Any?>): NotificationResult {
        return post(mapOf("channel" to channel, "payload" to payload))
    }

    override fun sendError(channel: String, title: String, details: String): NotificationResult {
        val payload = mapOf(
            "channel" to channel,
            "error" to mapOf("title" to title, "details" to details)
        )
        return post(payload)
    }

    private fun post(payload: Map<String, Any?>): NotificationResult {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return NotificationResult(
            ok = response.statusCode() in 200..299,
            statusCode = response.statusCode(),
            provider = providerName,
            responseBody = response.body()
        )
    }
}

class ConsoleNotificationsProvider : NotificationsProvider {
    override fun sendText(channel: String, text: String): NotificationResult {
        println("[notifications][$channel] $text")
        return NotificationResult(true, 200, "console", "ok")
    }

    override fun sendStructured(channel: String, payload: Map<String, Any?>): NotificationResult {
        println("[notifications][$channel] $payload")
        return NotificationResult(true, 200, "console", "ok")
    }

    override fun sendError(channel: String, title: String, details: String): NotificationResult {
        System.err.println("[notifications][$channel][$title] $details")
        return NotificationResult(true, 200, "console", "ok")
    }
}
