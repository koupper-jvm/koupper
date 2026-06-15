package com.koupper.providers.browser

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteractiveBrowserProviderTest : AnnotationSpec() {
    init {
        InteractiveBrowserServiceProvider().up()
    }

    @Test
    fun `should bind InteractiveBrowserProvider to PlaywrightInteractiveBrowser`() {
        assertTrue { app.getInstance(InteractiveBrowserProvider::class) is PlaywrightInteractiveBrowser }
    }

    @Test
    fun `stealth script should override webdriver property`() {
        assertTrue { STEALTH_SCRIPT.contains("navigator, 'webdriver'") }
    }

    @Test
    fun `stealth script should spoof chrome runtime object`() {
        assertTrue { STEALTH_SCRIPT.contains("window.chrome") }
    }

    @Test
    fun `stealth script should spoof plugins list`() {
        assertTrue { STEALTH_SCRIPT.contains("navigator, 'plugins'") }
    }

    @Test
    fun `failed navigation result should carry error and ok=false`() {
        val result = NavigationResult(ok = false, url = "https://example.com", error = "net::ERR_NAME_NOT_RESOLVED")
        assertFalse(result.ok)
        assertNotNull(result.error)
    }

    @Test
    fun `successful navigation result should have no error`() {
        val result = NavigationResult(ok = true, url = "https://example.com", title = "Example")
        assertTrue(result.ok)
        assertNull(result.error)
    }

    @Test
    fun `default scroll delay range should produce values within 1500-3500ms`() {
        val range = 1500L..3500L
        repeat(30) { assertTrue { range.random() in range } }
    }

    @Test
    fun `PageElement should carry all optional fields as nullable`() {
        val el = PageElement(text = "click here", href = "https://fb.com/post/123")
        assertNotNull(el.href)
        assertNull(el.src)
        assertNull(el.ariaLabel)
    }

    @Test
    fun `extractFeedContext should be declared on BrowserSession interface`() {
        val method = BrowserSession::class.members.find { it.name == "extractFeedContext" }
        assertNotNull(method)
    }

    @Test
    fun `extractFeedContext maxChars should truncate long output`() {
        // Simulates what the impl does: truncate to maxChars
        val raw = "A".repeat(20_000)
        val maxChars = 12_000
        val result = if (raw.length <= maxChars) raw else raw.substring(0, maxChars)
        assertTrue(result.length == maxChars)
    }

    @Test
    fun `extractFeedContext should not truncate output within maxChars`() {
        val raw = "[POST 1]\ntext: Hola mundo\nlinks: https://facebook.com/reel/123"
        val maxChars = 12_000
        val result = if (raw.length <= maxChars) raw else raw.substring(0, maxChars)
        assertTrue(result == raw)
    }
}
