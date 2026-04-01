package com.koupper.octopus

import com.koupper.container.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctopusArgsParserTest {
    private val octopus = Octopus(app)

    @Test
    fun `should keep negative numbers as positionals`() {
        val parsed = octopus.parseArgs("-1 -2.5 -3e2 --verbose")

        assertEquals(setOf("--verbose"), parsed.flags)
        assertTrue(parsed.params.isEmpty())
        assertEquals(listOf("-1", "-2.5", "-3e2"), parsed.positionals)
    }

    @Test
    fun `should parse key value tokens before flag detection`() {
        val parsed = octopus.parseArgs("-x=1 --env=prod retries=3")

        assertTrue(parsed.flags.isEmpty())
        assertEquals(
            linkedMapOf(
                "-x" to "1",
                "--env" to "prod",
                "retries" to "3"
            ),
            parsed.params
        )
    }

    @Test
    fun `parseArgs should be deterministic under concurrency`() = runBlocking {
        val input = "--debug retries=2 -1 user=jacob"

        val results = (1..200).map {
            async(Dispatchers.Default) { octopus.parseArgs(input) }
        }.awaitAll()

        results.forEach { parsed ->
            assertEquals(setOf("--debug"), parsed.flags)
            assertEquals(linkedMapOf("retries" to "2", "user" to "jacob"), parsed.params)
            assertEquals(listOf("-1"), parsed.positionals)
        }
    }
}
