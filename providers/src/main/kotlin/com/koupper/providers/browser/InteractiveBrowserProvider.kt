package com.koupper.providers.browser

enum class PostType { VIDEO, IMAGE, TEXT }

data class FeedPost(
    val text: String,
    val links: List<String>,
    val images: List<String>,
    val reactionCount: Int?,
    val commentCount: Int?,
    val postUrl: String?,
    val postType: PostType
)

data class PostComment(
    val author: String,
    val text: String,
    val reactionCount: Int? = null
)

data class PageElement(
    val text: String,
    val href: String? = null,
    val src: String? = null,
    val ariaLabel: String? = null,
    val role: String? = null
)

data class NavigationResult(
    val ok: Boolean,
    val url: String,
    val title: String = "",
    val error: String? = null
)

interface BrowserSession {
    fun navigate(url: String): NavigationResult
    fun scrollFeed(times: Int = 3, delayRange: LongRange = 1500L..3500L): BrowserSession
    fun humanClick(selector: String): BrowserSession
    fun waitFor(selector: String, timeoutMs: Long = 10_000L): Boolean
    fun extractElements(selector: String): List<PageElement>
    fun extractLinks(selector: String = "a[href]"): List<String>
    fun extractFeedContext(maxChars: Int = 12_000): String
    fun extractFeedPosts(maxPosts: Int = 15): List<FeedPost>
    fun extractComments(maxComments: Int = 20): List<PostComment>
    fun screenshotElementAt(selector: String, index: Int): ByteArray?
    fun evaluate(script: String): Any?
    fun screenshot(): ByteArray
    fun currentUrl(): String
    fun close()
}

interface InteractiveBrowserProvider {
    fun openSession(profileDir: String): BrowserSession
    fun close()
}
