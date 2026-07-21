package com.koupper.providers.search

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

interface WebSearchProvider {
    fun search(query: String, maxResults: Int = 5): List<SearchResult>
}
