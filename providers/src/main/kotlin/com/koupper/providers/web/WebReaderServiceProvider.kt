package com.koupper.providers.web

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class WebReaderServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(WebReaderProvider::class, {
            PlaywrightWebReaderProvider()
        })
    }
}
