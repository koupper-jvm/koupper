package com.koupper.shared.runtime

data class WebResponse(
    val body: Any,
    val statusCode: Int = 200,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun html(content: String, status: Int = 200) = WebResponse(content, status, "text/html; charset=UTF-8")
        fun json(content: Any, status: Int = 200) = WebResponse(content, status, "application/json")
        fun plain(content: String, status: Int = 200) = WebResponse(content, status, "text/plain")
        fun redirect(url: String) = WebResponse("", 302, null, mapOf("Location" to url))
    }
}
