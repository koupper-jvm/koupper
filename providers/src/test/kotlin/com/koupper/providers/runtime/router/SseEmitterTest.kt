package com.koupper.providers.runtime.router

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseEmitterTest : AnnotationSpec() {

    @Test
    fun `emits directly when a data callback is already registered`() {
        val emitter = SseEmitter()
        val received = mutableListOf<String>()
        emitter.onData { received.add(it) }

        emitter.emit("one")
        emitter.emit("two")

        assertEquals(listOf("one", "two"), received)
    }

    @Test
    fun `buffers events emitted before onData and replays them in order`() {
        val emitter = SseEmitter()
        emitter.emit("early-1")
        emitter.emit("early-2")

        val received = mutableListOf<String>()
        emitter.onData { received.add(it) }
        emitter.emit("late")

        assertEquals(listOf("early-1", "early-2", "late"), received)
    }

    @Test
    fun `does not replay buffered events twice`() {
        val emitter = SseEmitter()
        emitter.emit("early")

        val received = mutableListOf<String>()
        emitter.onData { received.add(it) }
        emitter.onData { received.add(it) }

        assertEquals(listOf("early"), received)
    }

    @Test
    fun `invokes close callback registered after complete`() {
        val emitter = SseEmitter()
        emitter.complete()

        var closed = false
        emitter.onClose { closed = true }

        assertTrue(closed)
    }

    @Test
    fun `invokes close callback registered before complete`() {
        val emitter = SseEmitter()
        var closed = false
        emitter.onClose { closed = true }

        emitter.complete()

        assertTrue(closed)
    }
}
