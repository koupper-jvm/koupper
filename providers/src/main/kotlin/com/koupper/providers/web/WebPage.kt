package com.koupper.providers.web

data class WebImage(
    val src: String,
    val alt: String,
    val width: Int? = null,
    val height: Int? = null
)

data class WebPage(
    val url: String,
    val title: String,
    val text: String,
    val description: String,
    val links: List<String>,
    val images: List<WebImage>
)
