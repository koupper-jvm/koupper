package com.koupper.providers.http

interface HtppClient {
    fun post(init: Post.() -> Unit): HttpResponse
    fun get(init: Get.() -> Unit): HttpResponse?
    fun put(init: Put.() -> Unit): HttpResponse
    fun patch(init: Patch.() -> Unit): HttpResponse
    fun delete(init: Delete.() -> Unit): HttpResponse
}