package com.koupper.providers.telegram

import io.kotest.core.spec.style.AnnotationSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun tempDir(): File = Files.createTempDirectory("telegram-test").toFile().also { it.deleteOnExit() }

// ── sendLongMessage (pure default-method logic) ───────────────────────────────

// Minimal impl that records sent chunks without hitting the network.
private class RecordingTelegram : TelegramChannelProvider {
    val sent = mutableListOf<String>()

    override fun startPolling(
        token: String, allowedChats: Set<Long>,
        running: () -> Boolean, offsetFile: File?,
        onMessage: (Long, String) -> Unit
    ) = Unit

    override fun sendMessage(token: String, chatId: Long, text: String) { if (text.isNotBlank()) sent += text }
    override fun sendPhoto(token: String, chatId: Long, file: File, caption: String) = Unit
}

class TelegramSendLongMessageTest : AnnotationSpec() {

    @Test
    fun `short text sent as single message`() {
        val t = RecordingTelegram()
        t.sendLongMessage("tok", 1L, "hello")
        assertEquals(listOf("hello"), t.sent)
    }

    @Test
    fun `empty text produces no send calls`() {
        val t = RecordingTelegram()
        // sendLongMessage delegates to sendMessage; RecordingTelegram ignores blank in sendMessage
        t.sendLongMessage("tok", 1L, "")
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun `text exactly at chunk size sent as single message`() {
        val t = RecordingTelegram()
        val text = "x".repeat(3800)
        t.sendLongMessage("tok", 1L, text, chunkSize = 3800)
        assertEquals(1, t.sent.size)
        assertEquals(text, t.sent[0])
    }

    @Test
    fun `text longer than chunk size split into multiple messages`() {
        val t = RecordingTelegram()
        val text = "a".repeat(3800) + "b".repeat(3800)
        t.sendLongMessage("tok", 1L, text, chunkSize = 3800)
        assertEquals(2, t.sent.size)
        assertEquals("a".repeat(3800), t.sent[0])
        assertEquals("b".repeat(3800), t.sent[1])
    }

    @Test
    fun `chunks cover all content without loss`() {
        val t = RecordingTelegram()
        val text = "z".repeat(10_000)
        t.sendLongMessage("tok", 1L, text, chunkSize = 3800)
        assertEquals(text, t.sent.joinToString(""))
    }
}

// ── startPolling — no-network behavior ───────────────────────────────────────

class TelegramStartPollingTest : AnnotationSpec() {

    @Test
    fun `startPolling exits immediately when running returns false`() {
        val impl = TelegramChannelProviderImpl()
        // running = { false } → while loop never enters → no HTTP call
        impl.startPolling("fake-token", running = { false }) { _, _ -> }
        // reaching here without exception is the assertion
    }

    @Test
    fun `startPolling does not invoke onMessage when running is false from start`() {
        val impl = TelegramChannelProviderImpl()
        var called = false
        impl.startPolling("fake-token", running = { false }) { _, _ -> called = true }
        assertFalse(called)
    }
}

// ── offsetFile persistence ────────────────────────────────────────────────────

class TelegramOffsetFileTest : AnnotationSpec() {

    @Test
    fun `offsetFile is not created when startPolling exits immediately`() {
        val dir  = tempDir()
        val file = File(dir, "offset.json")
        val impl = TelegramChannelProviderImpl()
        impl.startPolling("fake-token", running = { false }, offsetFile = file) { _, _ -> }
        // No updates processed → file never written
        assertFalse(file.exists())
    }

    @Test
    fun `startPolling accepts null offsetFile without throwing`() {
        val impl = TelegramChannelProviderImpl()
        impl.startPolling("fake-token", running = { false }, offsetFile = null) { _, _ -> }
    }
}

// ── sendPhoto — no-network behavior ──────────────────────────────────────────

class TelegramSendPhotoTest : AnnotationSpec() {

    @Test
    fun `sendPhoto with non-existent file does not throw`() {
        val impl    = TelegramChannelProviderImpl()
        val missing = File("/tmp/nonexistent_photo_xyz.jpg")
        impl.sendPhoto("fake-token", 1L, missing)
        // reaching here without exception is the assertion
    }

    @Test
    fun `sendPhoto with empty caption does not throw for non-existent file`() {
        val impl = TelegramChannelProviderImpl()
        impl.sendPhoto("fake-token", 1L, File("/no/such/file.png"), "")
    }
}

// ── catalog registration ──────────────────────────────────────────────────────

class TelegramCatalogTest : AnnotationSpec() {

    @Test
    fun `TelegramServiceProvider is registered in ServiceProviderManager`() {
        val providers = com.koupper.providers.ServiceProviderManager()
            .listProviders()
            .mapNotNull { it.simpleName }
        assertTrue("TelegramServiceProvider" in providers,
            "TelegramServiceProvider missing from ServiceProviderManager")
    }

    @Test
    fun `TelegramServiceProvider binds TelegramChannelProvider`() {
        val sp = TelegramServiceProvider()
        sp.up()
        val instance = com.koupper.container.app.getInstance(TelegramChannelProvider::class)
        assertTrue(instance is TelegramChannelProviderImpl)
    }
}
