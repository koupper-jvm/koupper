package com.koupper.providers.templates.loader

import java.util.concurrent.ConcurrentHashMap

/**
 * Decorator that caches [TemplateLoader.read] results in memory with a TTL.
 */
class CachedTemplateLoader(
    private val delegate: TemplateLoader,
    private val ttlMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : TemplateLoader {

    private data class Entry(val content: String, val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun read(path: String): String {
        if (ttlMillis <= 0L) return delegate.read(path)

        val now = clock()
        cache[path]?.let { entry ->
            if (entry.expiresAtMillis > now) return entry.content
            cache.remove(path, entry)
        }

        val content = delegate.read(path)
        cache[path] = Entry(content, now + ttlMillis)
        return content
    }

    /** Test/ops helper: drop all cached templates. */
    fun invalidateAll() {
        cache.clear()
    }
}
