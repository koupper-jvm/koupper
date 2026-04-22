package com.koupper.providers.http

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class HttpServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerHttpClient()
    }

    private fun registerHttpClient() {
        app.bind(HtppClient::class, {
            HttpInvoker()
        })
    }
}