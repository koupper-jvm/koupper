package com.koupper.providers.vision

class VisionAnalysis(
    val contentType: String  = "unknown",
    val summary: String      = "",
    val topics: String       = "",
    val sentiment: String    = "neutral",
    val controversy: String  = "low",
    val commentTone: String  = "irrelevant",
    val relevanceScore: Int  = 0,
    val keepImage: Boolean   = false,
    val reason: String       = ""
)

interface VisionProvider {
    /**
     * Sends an image + prompt to a vision-capable LLM and returns a typed [VisionAnalysis].
     * The prompt should request JSON with keys matching VisionAnalysis fields.
     * Greedy regex extraction handles markdown-fenced responses transparently.
     */
    fun analyze(imageBytes: ByteArray, prompt: String): VisionAnalysis

    /**
     * Returns the raw text content from the LLM, unparsed.
     * Useful for prose responses or custom JSON schemas.
     */
    fun analyzeAsText(imageBytes: ByteArray, prompt: String): String
}
