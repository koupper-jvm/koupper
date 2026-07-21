package com.koupper.providers.browser

import com.koupper.os.env
import com.koupper.os.envOptional
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import java.nio.file.Path

internal val STEALTH_SCRIPT = """
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){}, app: {} };
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
    Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
    const _origQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (p) =>
        p.name === 'notifications'
            ? Promise.resolve({ state: Notification.permission })
            : _origQuery(p);
""".trimIndent()

class PlaywrightBrowserSession(
    private val context: BrowserContext,
    private val page: Page
) : BrowserSession {

    override fun navigate(url: String): NavigationResult {
        return try {
            page.navigate(url, Page.NavigateOptions().setTimeout(30_000.0))
            page.waitForLoadState(LoadState.DOMCONTENTLOADED)
            NavigationResult(ok = true, url = page.url(), title = page.title())
        } catch (e: Exception) {
            NavigationResult(ok = false, url = url, error = e.message)
        }
    }

    override fun scrollFeed(times: Int, delayRange: LongRange): BrowserSession {
        repeat(times) {
            val px = (300..700).random()
            page.evaluate("window.scrollBy({ top: $px, behavior: 'smooth' })")
            Thread.sleep(delayRange.random())
        }
        return this
    }

    override fun humanClick(selector: String): BrowserSession {
        runCatching {
            val el = page.locator(selector).first()
            el.hover()
            Thread.sleep((200L..600L).random())
            el.click()
            Thread.sleep((800L..2000L).random())
        }
        return this
    }

    override fun waitFor(selector: String, timeoutMs: Long): Boolean = runCatching {
        page.waitForSelector(selector, Page.WaitForSelectorOptions().setTimeout(timeoutMs.toDouble()))
        true
    }.getOrDefault(false)

    override fun extractElements(selector: String): List<PageElement> =
        page.querySelectorAll(selector).map { el ->
            PageElement(
                text = el.innerText().trim(),
                href = el.getAttribute("href"),
                src = el.getAttribute("src"),
                ariaLabel = el.getAttribute("aria-label"),
                role = el.getAttribute("role")
            )
        }

    override fun extractLinks(selector: String): List<String> =
        page.querySelectorAll(selector)
            .mapNotNull { it.getAttribute("href") }
            .filter { it.startsWith("http") }
            .distinct()

    override fun extractFeedContext(maxChars: Int): String {
        val raw = page.evaluate("""
            (function() {
                const VIDEO_PATTERNS = ['/reel/', '/videos/', '/watch'];
                const isVideoLink = href => href && VIDEO_PATTERNS.some(p => href.includes(p));

                const articles = Array.from(document.querySelectorAll('[role="article"]'));
                if (articles.length === 0) {
                    return '[FALLBACK] ' + document.body.innerText
                        .replace(/\s+/g, ' ').trim().substring(0, 8000);
                }

                return articles.map((el, i) => {
                    const text = el.innerText.replace(/\s+/g, ' ').trim().substring(0, 400);
                    const links = Array.from(el.querySelectorAll('a[href]'))
                        .map(a => a.href)
                        .filter(isVideoLink)
                        .filter((v, idx, arr) => arr.indexOf(v) === idx)
                        .slice(0, 6)
                        .join(' | ');
                    return '[POST ' + (i + 1) + ']\ntext: ' + text + '\nlinks: ' + (links || 'none');
                }).join('\n\n');
            })()
        """.trimIndent()).toString()

        return if (raw.length <= maxChars) raw else raw.substring(0, maxChars)
    }

    @Suppress("UNCHECKED_CAST")
    override fun extractFeedPosts(maxPosts: Int): List<FeedPost> {
        val raw = page.evaluate("""
            (function(max) {
                const VP = ['/reel/', '/videos/', '/watch'];
                const articles = Array.from(document.querySelectorAll('[role="article"]')).slice(0, max);
                return articles.map(el => {
                    const text = el.innerText.replace(/\s+/g, ' ').trim().substring(0, 600);
                    const links = Array.from(el.querySelectorAll('a[href]'))
                        .map(a => a.href).filter(h => VP.some(p => h.includes(p)))
                        .filter((v, i, a) => a.indexOf(v) === i);
                    const images = Array.from(el.querySelectorAll('img[src]'))
                        .map(img => img.src).filter(s => s.includes('fbcdn') && !s.includes('emoji'));

                    // Reaction count: aria-label on reaction button (e.g. "123 reactions")
                    const rEl = el.querySelector('[aria-label*="eaction"]') ||
                                el.querySelector('[aria-label*="Like"]');
                    const rText = rEl ? (rEl.getAttribute('aria-label') || rEl.textContent) : '';
                    const reactionCount = parseInt(rText.replace(/[^0-9]/g, '')) || null;

                    // Comment count: span containing "N comment(s)"
                    const cEl = Array.from(el.querySelectorAll('span'))
                        .find(s => /\d+\s*[Cc]omment/.test(s.textContent));
                    const commentCount = cEl ? parseInt(cEl.textContent.replace(/[^0-9]/g, '')) || null : null;

                    // Permalink
                    const plEl = el.querySelector('a[href*="/posts/"], a[href*="story_fbid"], a[href*="/permalink/"]');
                    const postUrl = plEl ? plEl.href : null;

                    const hasVideo = links.length > 0 || !!el.querySelector('video');
                    const postType = hasVideo ? 'VIDEO' : images.length > 0 ? 'IMAGE' : 'TEXT';

                    return { text, links, images, reactionCount, commentCount, postUrl, postType };
                });
            })($maxPosts)
        """.trimIndent()) as List<Map<String, Any?>>

        return raw.map { m ->
            FeedPost(
                text = m["text"] as? String ?: "",
                links = (m["links"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                images = (m["images"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                reactionCount = (m["reactionCount"] as? Number)?.toInt(),
                commentCount = (m["commentCount"] as? Number)?.toInt(),
                postUrl = m["postUrl"] as? String,
                postType = when (m["postType"] as? String) {
                    "VIDEO" -> PostType.VIDEO
                    "IMAGE" -> PostType.IMAGE
                    else -> PostType.TEXT
                }
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun extractComments(maxComments: Int): List<PostComment> {
        // On permalink pages the first [role="article"] is the post; the rest are comments
        val raw = page.evaluate("""
            (function(max) {
                const articles = Array.from(document.querySelectorAll('[role="article"]')).slice(1, max + 1);
                return articles.map(el => {
                    const label = el.getAttribute('aria-label') || '';
                    const author = label.replace(/^Comment by /, '').split(' ·')[0].trim() || 'Unknown';
                    const text = el.innerText.replace(/\s+/g, ' ').trim().substring(0, 400);
                    return { author, text };
                }).filter(c => c.text.length > 2);
            })($maxComments)
        """.trimIndent()) as List<Map<String, Any?>>

        return raw.map { m ->
            PostComment(
                author = m["author"] as? String ?: "Unknown",
                text = m["text"] as? String ?: ""
            )
        }
    }

    override fun screenshotElementAt(selector: String, index: Int): ByteArray? = runCatching {
        page.querySelectorAll(selector).getOrNull(index)?.screenshot()
    }.getOrNull()

    override fun evaluate(script: String): Any? = page.evaluate(script)

    override fun screenshot(): ByteArray = page.screenshot(Page.ScreenshotOptions().setTimeout(8_000.0))

    override fun currentUrl(): String = page.url()

    override fun type(text: String): BrowserSession {
        page.keyboard().type(text)
        return this
    }

    override fun pressKey(key: String): BrowserSession {
        page.keyboard().press(key)
        return this
    }

    override fun uploadFile(selector: String, filePath: String): BrowserSession {
        page.locator(selector).first().setInputFiles(Path.of(filePath))
        return this
    }

    override fun close() {
        runCatching { page.close() }
        runCatching { context.close() }
    }
}

class PlaywrightInteractiveBrowser : InteractiveBrowserProvider {
    private val playwright by lazy { Playwright.create() }
    private val userAgent = env(
        "BROWSER_USER_AGENT",
        required = false,
        default = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    )
    private val channel = envOptional("BROWSER_CHANNEL", "chrome")

    override fun openSession(profileDir: String): BrowserSession {
        val context = playwright.chromium().launchPersistentContext(
            Path.of(profileDir),
            BrowserType.LaunchPersistentContextOptions()
                .setHeadless(false)
                .setChannel(channel)
                .setUserAgent(userAgent)
                .setViewportSize(1366, 768)
                .setArgs(listOf(
                    "--disable-blink-features=AutomationControlled",
                    "--disable-infobars",
                    "--no-sandbox",
                    "--disable-dev-shm-usage"
                ))
        )
        context.addInitScript(STEALTH_SCRIPT)
        return PlaywrightBrowserSession(context, context.newPage())
    }

    override fun connectToExisting(cdpEndpoint: String): BrowserSession {
        val browser  = playwright.chromium().connectOverCDP(cdpEndpoint)
        val context  = browser.contexts().firstOrNull() ?: browser.newContext()
        val page     = context.pages().firstOrNull() ?: context.newPage()
        return PlaywrightBrowserSession(context, page)
    }

    override fun close() {
        runCatching { playwright.close() }
    }
}
