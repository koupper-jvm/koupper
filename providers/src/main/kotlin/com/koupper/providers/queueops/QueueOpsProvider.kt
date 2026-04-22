package com.koupper.providers.queueops

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Instant
import java.util.UUID

data class QueueMessage(
    val id: String = UUID.randomUUID().toString(),
    val queue: String,
    val payload: String,
    val state: String = "pending",
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString()
)

interface QueueOpsProvider {
    fun enqueue(queue: String, payload: String): QueueMessage
    fun listPending(queue: String): List<QueueMessage>
    fun requeue(id: String): QueueMessage
    fun deadLetter(id: String): QueueMessage
    fun purge(queue: String): Int
}

class LocalQueueOpsProvider(
    private val storePath: String = ".koupper-queue-ops.json"
) : QueueOpsProvider {
    private val mapper = jacksonObjectMapper()

    override fun enqueue(queue: String, payload: String): QueueMessage {
        val items = load().toMutableList()
        val message = QueueMessage(queue = queue, payload = payload)
        items += message
        save(items)
        return message
    }

    override fun listPending(queue: String): List<QueueMessage> {
        return load().filter { it.queue == queue && it.state == "pending" }
    }

    override fun requeue(id: String): QueueMessage {
        val items = load().toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) error("message '$id' not found")
        val updated = items[idx].copy(state = "pending", updatedAt = Instant.now().toString())
        items[idx] = updated
        save(items)
        return updated
    }

    override fun deadLetter(id: String): QueueMessage {
        val items = load().toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) error("message '$id' not found")
        val updated = items[idx].copy(state = "dead-letter", updatedAt = Instant.now().toString())
        items[idx] = updated
        save(items)
        return updated
    }

    override fun purge(queue: String): Int {
        val items = load().toMutableList()
        val before = items.size
        val kept = items.filterNot { it.queue == queue && it.state == "pending" }
        save(kept)
        return before - kept.size
    }

    private fun load(): List<QueueMessage> {
        val file = File(storePath)
        if (!file.exists() || file.readText().isBlank()) {
            return emptyList()
        }
        return mapper.readValue(file.readText(), object : TypeReference<List<QueueMessage>>() {})
    }

    private fun save(items: List<QueueMessage>) {
        val file = File(storePath)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(items))
    }
}
