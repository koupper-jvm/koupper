package com.koupper.providers.http

import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File

sealed class HttpClient {
    var host: String = ""
    var path: String? = null
    var url: String? = null
    lateinit var method: String
    val params: MutableMap<String, String> = mutableMapOf()
    val headers: MutableMap<String, String> = mutableMapOf()

    open fun makeBody(): RequestBody = throw UnsupportedOperationException("Request body is not supported for [$method] method.")
}

class Post : HttpClient() {
    private var body: RequestBody = RequestBody.create(null, byteArrayOf())

    fun body(contentType: String? = null, init: BodyRequest.() -> RequestBody) {
        body = BodyRequest(contentType).init()
    }

    fun multipartBody(contentType: String? = null, init: MultipartBodyRequest.() -> Unit) {
        body = MultipartBodyRequest(contentType).apply(init).build()
    }

    override fun makeBody(): RequestBody = body
}

class BodyRequest(type: String?) {
    private val mediaType = type?.toMediaTypeOrNull()

    fun string(content: String): RequestBody = RequestBody.create(mediaType, content)
    fun file(content: File): RequestBody = RequestBody.create(mediaType, content)
    fun bytes(content: ByteArray): RequestBody = RequestBody.create(mediaType, content)
    fun json(content: String): RequestBody = RequestBody.create("application/json".toMediaTypeOrNull(), content)
    fun form(content: String): RequestBody = RequestBody.create("application/x-www-form-urlencoded".toMediaTypeOrNull(), content)
}

class MultipartBodyRequest(type: String?) {
    private val builder = MultipartBody.Builder().setType(
        type?.toMediaTypeOrNull() ?: MultipartBody.FORM
    )

    fun part(name: String, filename: String? = null, init: BodyRequest.() -> RequestBody) {
        val body = BodyRequest(null).init()
        if (filename != null)
            builder.addFormDataPart(name, filename, body)
        else
            builder.addFormDataPart(name, "", body)
    }

    fun build(): MultipartBody = builder.build()
}

class Get : HttpClient()
class Put : HttpClient()
class Patch : HttpClient()
class Delete : HttpClient()

class HttpInvoker : HttpClient(), HtppClient {
    private val client = OkHttpClient()

    private fun buildRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: RequestBody? = null
    ): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }

        when (method.uppercase()) {
            "POST" -> builder.post(body ?: RequestBody.create(null, ByteArray(0)))
            "PUT" -> builder.put(body ?: RequestBody.create(null, ByteArray(0)))
            "PATCH" -> builder.patch(body ?: RequestBody.create(null, ByteArray(0)))
            "DELETE" -> {
                if (body != null) builder.delete(body) else builder.delete()
            }
        }

        return builder.build()
    }

    override fun post(init: Post.() -> Unit): HttpResponse {
        val post = Post().apply(init)
        val req = buildRequest("POST", post.url!!, post.headers, post.makeBody())
        val resp = client.newCall(req).execute()
        return HttpResponse(resp)
    }

    override fun get(init: Get.() -> Unit): HttpResponse? {
        val get = Get().apply(init)

        val httpUrlBuilder = get.url!!.toHttpUrlOrNull()?.newBuilder() ?: return null
        get.params.forEach { (k, v) -> httpUrlBuilder.addQueryParameter(k, v) }

        val req = Request.Builder()
            .url(httpUrlBuilder.build())
            .headers(get.headers.toHeaders())
            .get()
            .build()

        val resp = client.newCall(req).execute()
        return HttpResponse(resp)
    }

    override fun put(init: Put.() -> Unit): HttpResponse {
        val put = Put().apply(init)
        val req = buildRequest("PUT", put.url!!, put.headers, put.makeBody())
        val resp = client.newCall(req).execute()
        return HttpResponse(resp)
    }

    override fun patch(init: Patch.() -> Unit): HttpResponse {
        val patch = Patch().apply(init)
        val req = buildRequest("PATCH", patch.url!!, patch.headers, patch.makeBody())
        val resp = client.newCall(req).execute()
        return HttpResponse(resp)
    }

    override fun delete(init: Delete.() -> Unit): HttpResponse {
        val delete = Delete().apply(init)
        val req = buildRequest("DELETE", delete.url!!, delete.headers, null)
        val resp = client.newCall(req).execute()
        return HttpResponse(resp)
    }
}
