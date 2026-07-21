package com.koupper.providers.http

import com.koupper.container.app
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

class HttpServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.CORE

    override fun up() {
        this.registerHttpClient()
    }

    override fun externalDependencies() = listOf("com.squareup.okhttp3:okhttp:4.12.0")

    private fun registerHttpClient() {
        app.bind(HtppClient::class, {
            HttpInvoker()
        })
    }
}
