package com.koupper.providers.vectordb

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorDbProviderTest : AnnotationSpec() {

    private fun provider() = LocalVectorDbProvider()

    @Test
    fun `upsert returns count of inserted vectors`() {
        val p = provider()
        val count = p.upsert("test", listOf(
            VectorRecord("a", listOf(1.0, 0.0)),
            VectorRecord("b", listOf(0.0, 1.0))
        ))
        assertEquals(2, count)
    }

    @Test
    fun `query returns nearest vector by cosine similarity`() {
        val p = provider()
        p.upsert("col", listOf(
            VectorRecord("near", listOf(1.0, 0.0)),
            VectorRecord("far",  listOf(0.0, 1.0))
        ))
        val results = p.query("col", listOf(1.0, 0.0), topK = 1)
        assertEquals(1, results.size)
        assertEquals("near", results.first().id)
    }

    @Test
    fun `query returns empty list for unknown collection`() {
        val results = provider().query("missing", listOf(1.0, 0.0))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `query respects topK limit`() {
        val p = provider()
        p.upsert("col", (1..10).map { VectorRecord("v$it", listOf(it.toDouble(), 0.0)) })
        val results = p.query("col", listOf(1.0, 0.0), topK = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `query filters by metadata`() {
        val p = provider()
        p.upsert("col", listOf(
            VectorRecord("x", listOf(1.0, 0.0), mapOf("env" to "prod")),
            VectorRecord("y", listOf(1.0, 0.0), mapOf("env" to "dev"))
        ))
        val results = p.query("col", listOf(1.0, 0.0), filter = mapOf("env" to "prod"))
        assertEquals(1, results.size)
        assertEquals("x", results.first().id)
    }

    @Test
    fun `delete removes records and returns count`() {
        val p = provider()
        p.upsert("col", listOf(
            VectorRecord("del1", listOf(1.0, 0.0)),
            VectorRecord("del2", listOf(0.0, 1.0)),
            VectorRecord("keep", listOf(1.0, 1.0))
        ))
        val deleted = p.delete("col", listOf("del1", "del2"))
        assertEquals(2, deleted)
        val remaining = p.query("col", listOf(1.0, 0.0), topK = 10)
        assertEquals(1, remaining.size)
        assertEquals("keep", remaining.first().id)
    }

    @Test
    fun `cosine similarity between identical vectors is 1`() {
        val p = provider()
        p.upsert("col", listOf(VectorRecord("same", listOf(0.6, 0.8))))
        val results = p.query("col", listOf(0.6, 0.8))
        assertEquals(1.0, results.first().score, 1e-9)
    }
}
