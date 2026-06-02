package com.koupper.providers.search

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class WebSearchServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(WebSearchProvider::class, {
            DuckDuckGoSearchProvider()
        })
    }
}
