package com.koupper.octopus

object SecretRedactor {
    private val redactPatterns = ThreadLocal<MutableSet<String>>()
    private val enabled = ThreadLocal<Boolean>()

    fun enable(patterns: Set<String>) {
        enabled.set(true)
        redactPatterns.set(patterns.toMutableSet())
    }

    fun disable() {
        enabled.set(false)
        redactPatterns.set(mutableSetOf())
    }

    fun redact(text: String): String {
        if (enabled.get() != true) return text
        val patterns = redactPatterns.get() ?: return text
        var result = text
        for (pattern in patterns) {
            if (pattern.isNotBlank()) {
                result = result.replace(pattern, "***")
            }
        }
        return result
    }
}
