package com.koupper.providers.http

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class HttpServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind http client`() {
        HttpServiceProvider().up()

        assertTrue {
            app.createInstanceOf(HtppClient::class) is HttpClient
        }

        val result = app.createInstanceOf(HtppClient::class).get {
            this.url = "https://www.instagram.com/"
        }

        result?.asString()!!
    }
}