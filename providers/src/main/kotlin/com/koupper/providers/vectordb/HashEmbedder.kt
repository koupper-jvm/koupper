package com.koupper.providers.vectordb

import kotlin.math.sqrt

private const val DIMS = 512

object HashEmbedder {
    fun embed(text: String): List<Double> {
        val vec = DoubleArray(DIMS)
        val tokens = text.lowercase()
            .split(Regex("[\\s,.:;!?\"'()\\[\\]{}<>/\\\\@#$%^&*+=|~`]+"))
            .filter { it.isNotBlank() }

        for (token in tokens) {
            accumulate(vec, token)
            // bigrams for better context
            for (i in 0 until token.length - 1) {
                accumulate(vec, token.substring(i, i + 2))
            }
        }
        return normalize(vec)
    }

    private fun accumulate(vec: DoubleArray, token: String) {
        var h = token.hashCode().toLong() and 0xFFFFFFFFL
        // spread into two buckets per token to reduce collisions
        val b1 = (h % DIMS).toInt()
        h = h xor (h shr 16); h = (h * 0x45d9f3bL) and 0xFFFFFFFFL
        val b2 = (h % DIMS).toInt()
        vec[b1] += 1.0
        vec[b2] += 0.5
    }

    private fun normalize(vec: DoubleArray): List<Double> {
        val mag = sqrt(vec.sumOf { it * it })
        return if (mag == 0.0) vec.toList() else vec.map { it / mag }
    }
}
