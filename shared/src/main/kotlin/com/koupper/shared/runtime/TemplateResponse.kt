package com.koupper.shared.runtime

data class TemplateResponse(
    val templatePath: String,
    val context: Map<String, Any?> = emptyMap(),
    val statusCode: Int = 200,
    val headers: Map<String, String> = emptyMap()
)
