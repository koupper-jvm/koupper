package com.koupper.providers.http

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class HttpServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind http client`() {
        HttpServiceProvider().up()

        assertTrue {
            app.createInstanceOf(Client::class) is HttpClient
        }
    }
}