package com.koupper.providers.commandbridge

import io.kotest.core.spec.style.AnnotationSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests use a short dedup window (100ms) so expiry can be verified without long sleeps.
private const val SHORT_WINDOW_MS = 100L
private const val POLL_MS         = 1_200L   // generous poll — inotify on Linux is fast but not instant

private fun tempDir(): File =
    Files.createTempDirectory("cmd-bridge-test").toFile().also { it.deleteOnExit() }

private fun writeResponse(dir: File, name: String, content: String): File =
    File(dir, "$name.response").also { it.writeText(content) }

class CommandBridgeProviderWatchTest : AnnotationSpec() {

    @Test
    fun `watch creates directory when it does not exist`() {
        val dir = File(tempDir(), "nonexistent/nested")
        assertNull(dir.listFiles())   // does not exist yet

        CommandBridgeProviderImpl().watch(dir).close()

        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `watch returns self for fluent chaining`() {
        val dir = tempDir()
        val impl = CommandBridgeProviderImpl()
        val result = impl.watch(dir)
        assertTrue(result === impl)
        impl.close()
    }
}

class CommandBridgeProviderDrainTest : AnnotationSpec() {

    @Test
    fun `drain deletes stale response files`() {
        val dir = tempDir()
        val stale = writeResponse(dir, "old-cmd", "stale content")

        CommandBridgeProviderImpl().watch(dir).drain().close()

        assertTrue(!stale.exists())
    }

    @Test
    fun `drain leaves non-response files intact`() {
        val dir = tempDir()
        val other = File(dir, "notes.txt").also { it.writeText("keep me") }

        CommandBridgeProviderImpl().watch(dir).drain().close()

        assertTrue(other.exists())
    }

    @Test
    fun `drain returns self for fluent chaining`() {
        val dir = tempDir()
        val impl = CommandBridgeProviderImpl()
        val result = impl.watch(dir).drain()
        assertTrue(result === impl)
        impl.close()
    }
}

class CommandBridgeProviderNextCommandTest : AnnotationSpec() {

    @Test
    fun `nextCommand returns null when no file arrives within timeout`() {
        val dir = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        try {
            val result = impl.nextCommand(300L)
            assertNull(result)
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand reads content and deletes the response file`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        try {
            val file = writeResponse(dir, "cmd-001", "hello cortex")
            val result = impl.nextCommand(POLL_MS)

            assertEquals("hello cortex", result)
            assertTrue(!file.exists(), "response file should be deleted after reading")
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand trims whitespace from content`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        try {
            writeResponse(dir, "cmd-002", "  trimmed message  \n")
            val result = impl.nextCommand(POLL_MS)
            assertEquals("trimmed message", result)
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand ignores non-response files`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        try {
            File(dir, "note.txt").writeText("not a command")
            val result = impl.nextCommand(400L)
            assertNull(result)
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand ignores blank response files`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        try {
            writeResponse(dir, "cmd-blank", "   ")
            val result = impl.nextCommand(POLL_MS)
            assertNull(result)
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand returns null when called without watch`() {
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS)
        val result = impl.nextCommand(100L)
        assertNull(result)
    }
}

class CommandBridgeProviderDedupTest : AnnotationSpec() {

    @Test
    fun `nextCommand deduplicates same content within window`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(dedupWindowMs = 5_000L).watch(dir)
        try {
            writeResponse(dir, "cmd-a", "deploy now")
            val first = impl.nextCommand(POLL_MS)
            assertEquals("deploy now", first)

            // Second file with same content — should be deduplicated
            writeResponse(dir, "cmd-b", "deploy now")
            val second = impl.nextCommand(POLL_MS)
            assertNull(second, "duplicate content within window should be suppressed")
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand accepts same content after window expires`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(dedupWindowMs = SHORT_WINDOW_MS).watch(dir)
        try {
            writeResponse(dir, "cmd-x", "run agent")
            val first = impl.nextCommand(POLL_MS)
            assertEquals("run agent", first)

            // Wait for the dedup window to expire
            Thread.sleep(SHORT_WINDOW_MS + 50)

            writeResponse(dir, "cmd-y", "run agent")
            val second = impl.nextCommand(POLL_MS)
            assertEquals("run agent", second, "same content should be accepted after window expires")
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand accepts different content regardless of window`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(dedupWindowMs = 5_000L).watch(dir)
        try {
            writeResponse(dir, "cmd-1", "first command")
            val first = impl.nextCommand(POLL_MS)
            assertEquals("first command", first)

            writeResponse(dir, "cmd-2", "second command")
            val second = impl.nextCommand(POLL_MS)
            assertNotNull(second, "different content should never be deduplicated")
            assertEquals("second command", second)
        } finally {
            impl.close()
        }
    }

    @Test
    fun `nextCommand filename-based dedup no longer hides same-content different-name files`() {
        // Regression: old implementation used filename as dedup key.
        // Two files with different names but same content should deduplicate on content,
        // not pass through both.
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(dedupWindowMs = 5_000L).watch(dir)
        try {
            // Write both files before polling so they may appear in one WatchService batch
            writeResponse(dir, "alpha", "same text")
            Thread.sleep(50)
            writeResponse(dir, "beta",  "same text")

            val first  = impl.nextCommand(POLL_MS)
            val second = impl.nextCommand(POLL_MS)

            assertEquals("same text", first)
            // The second must be null — content-based dedup eliminates it
            assertNull(second, "same content from a different filename must still be deduplicated")
        } finally {
            impl.close()
        }
    }
}

class CommandBridgeProviderCloseTest : AnnotationSpec() {

    @Test
    fun `nextCommand returns null after close`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        impl.close()

        val result = impl.nextCommand(100L)
        assertNull(result)
    }

    @Test
    fun `close is idempotent`() {
        val dir  = tempDir()
        val impl = CommandBridgeProviderImpl(SHORT_WINDOW_MS).watch(dir)
        impl.close()
        impl.close()   // must not throw
    }
}
