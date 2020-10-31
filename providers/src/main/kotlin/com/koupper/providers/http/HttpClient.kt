package com.koupper.providers.http

import io.github.rybalkinsd.kohttp.dsl.*
import io.github.rybalkinsd.kohttp.dsl.context.BodyContext
import io.github.rybalkinsd.kohttp.dsl.context.MultipartBodyContext
import io.github.rybalkinsd.kohttp.ext.url
import io.github.rybalkinsd.kohttp.util.Form
import io.github.rybalkinsd.kohttp.util.Json
import okhttp3.*
import java.io.File

sealed class HttpClient {
    var host: String = ""
    var path: String? = null
    var url: String? = null
    lateinit var method: String
    val params: MutableMap<String, Any> = mutableMapOf()
    val headers: MutableMap<String, Any> = mutableMapOf()

    open fun makeBody(): RequestBody = throw UnsupportedOperationException("Request body is not supported for [$method] Method.")
}

class Post : HttpClient() {
    private var body: RequestBody = RequestBody.create(null, byteArrayOf())

    fun body(contentType: String? = null, init: BodyRequest.() -> RequestBody) {
        body = BodyRequest(contentType).init()
    }

    fun multipartBody(contentType: String? = null, init: MultipartBodyRequest.() -> Unit) {
        body = MultipartBodyRequest(contentType).apply { init() }.build()
    }

    override fun makeBody(): RequestBody = body
}

class BodyRequest(type: String?) {
    private val bodyContext = BodyContext(type)

    fun string(content: String): RequestBody = bodyContext.string(content)
    fun file(content: File): RequestBody = bodyContext.file(content)
    fun bytes(content: ByteArray): RequestBody = bodyContext.bytes(content)

    fun json(content: String): RequestBody = bodyContext.json(content)
    fun form(content: String): RequestBody = bodyContext.form(content)

    fun json(init: Json.() -> Unit): RequestBody = bodyContext.json(init)
    fun form(init: Form.() -> Unit): RequestBody = bodyContext.form(init)
}

class MultipartBodyRequest(type: String?) {
    private val multipartBodyContext = MultipartBodyContext(type)

    fun part(name: String, filename: String? = null, init: BodyContext.() -> RequestBody): MultipartBody.Part =
            multipartBodyContext.part(name, filename, init)

    fun build(): MultipartBody = multipartBodyContext.build()
}

class Get : HttpClient()

class Put : HttpClient()

class Patch : HttpClient()

class Delete : HttpClient()

class HttpInvoker : HttpClient(), Client {
    private lateinit var response: Response

    override fun post(init: Post.() -> Unit): HttpResponse {
        this.method = "POST"

        val post = Post().apply(init)

        val response = httpPost {
            host = post.host
            path = post.path
            param {
                post.params
            }
            header {
                post.headers
            }
            body {
                form {
                    post.makeBody()
                }
            }
        }

        return HttpResponse(response)
    }

    override fun get(init: Get.() -> Unit): HttpResponse? {
        this.method = "GET"

        val get = Get().apply(init)

        val response = httpGet {
            url(get.url!!)
            param {
                get.params
            }
        }

        return HttpResponse(response)
    }

    override fun put(init: Put.() -> Unit): HttpResponse {
        this.method = "PUT"

        val put = Put().apply(init)

        val response = httpPut {
            host = put.host
            path = put.path
            param {
                put.params
            }
            header {
                put.headers
            }
        }

        return HttpResponse(response)
    }

    override fun patch(init: Patch.() -> Unit): HttpResponse {
        this.method = "PATCH"

        val patch = Patch().apply(init)

        val response = httpPatch {
            host = patch.host
            path = patch.path
            param {
                patch.params
            }
            header {
                patch.headers
            }
        }

        return HttpResponse(response)
    }

    override fun delete(init: Delete.() -> Unit): HttpResponse {
        this.method = "DELETE"

        val delete = Delete().apply(init)

        val response = httpDelete {
            host = delete.host
            path = delete.path
            param {
                delete.params
            }
            header {
                delete.headers
            }
        }

        return HttpResponse(response)
    }
}