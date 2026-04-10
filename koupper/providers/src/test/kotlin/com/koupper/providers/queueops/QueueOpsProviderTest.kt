package com.koupper.providers.queueops

import io.kotest.core.spec.style.AnnotationSpec
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueOpsProviderTest : AnnotationSpec() {

    private fun provider(): LocalQueueOpsProvider {
        val tmp = Files.createTempFile("koupper-queue-test", ".json").toFile()
        tmp.deleteOnExit()
        return LocalQueueOpsProvider(storePath = tmp.absolutePath)
    }

    @Test
    fun `enqueue returns message with pending state`() {
        val msg = provider().enqueue("orders", """{"id":1}""")
        assertEquals("orders", msg.queue)
        assertEquals("pending", msg.state)
        assertTrue(msg.id.isNotBlank())
    }

    @Test
    fun `listPending returns only pending messages for the queue`() {
        val p = provider()
        p.enqueue("q1", "a")
        p.enqueue("q1", "b")
        p.enqueue("q2", "c")
        val pending = p.listPending("q1")
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.queue == "q1" && it.state == "pending" })
    }

    @Test
    fun `deadLetter changes state to dead-letter`() {
        val p = provider()
        val msg = p.enqueue("q", "payload")
        val dead = p.deadLetter(msg.id)
        assertEquals("dead-letter", dead.state)
        assertTrue(p.listPending("q").isEmpty())
    }

    @Test
    fun `requeue restores dead-letter message to pending`() {
        val p = provider()
        val msg = p.enqueue("q", "x")
        p.deadLetter(msg.id)
        val restored = p.requeue(msg.id)
        assertEquals("pending", restored.state)
        assertEquals(1, p.listPending("q").size)
    }

    @Test
    fun `purge removes all pending messages and returns count`() {
        val p = provider()
        p.enqueue("q", "1")
        p.enqueue("q", "2")
        p.enqueue("q", "3")
        val removed = p.purge("q")
        assertEquals(3, removed)
        assertTrue(p.listPending("q").isEmpty())
    }

    @Test
    fun `purge does not affect other queues`() {
        val p = provider()
        p.enqueue("q1", "keep")
        p.enqueue("q2", "remove")
        p.purge("q2")
        assertEquals(1, p.listPending("q1").size)
    }
}
