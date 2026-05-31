package com.koupper.providers.web

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState

class PlaywrightWebReaderProvider : WebReaderProvider {

    private val playwright by lazy { Playwright.create() }
    private val browser by lazy {
        playwright.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
        )
    }

    override fun fetch(url: String): WebPage {
        val page = browser.newPage()
        try {
            page.navigate(url, Page.NavigateOptions().setTimeout(30_000.0))
            page.waitForLoadState(LoadState.NETWORKIDLE)

            val title       = page.title()
            val description = page.evaluate(
                "document.querySelector('meta[name=\"description\"]')?.getAttribute('content') ?? ''"
            ).toString()
            val text  = page.innerText("body").take(8_000)
            val links = page.querySelectorAll("a[href]")
                .mapNotNull { it.getAttribute("href") }
                .filter { it.startsWith("http") }
                .distinct()
            val images = page.querySelectorAll("img").map { el ->
                WebImage(
                    src    = el.getAttribute("src") ?: "",
                    alt    = el.getAttribute("alt") ?: "",
                    width  = el.getAttribute("width")?.toIntOrNull(),
                    height = el.getAttribute("height")?.toIntOrNull()
                )
            }.filter { it.src.isNotBlank() }

            return WebPage(url, title, text, description, links, images)
        } finally {
            page.close()
        }
    }

    override fun fetchText(url: String): String = fetch(url).text

    override fun screenshot(url: String): ByteArray {
        val page = browser.newPage()
        try {
            page.navigate(url, Page.NavigateOptions().setTimeout(30_000.0))
            page.waitForLoadState(LoadState.NETWORKIDLE)
            return page.screenshot(Page.ScreenshotOptions().setFullPage(false))
        } finally {
            page.close()
        }
    }

    fun close() {
        runCatching { browser.close() }
        runCatching { playwright.close() }
    }
}
