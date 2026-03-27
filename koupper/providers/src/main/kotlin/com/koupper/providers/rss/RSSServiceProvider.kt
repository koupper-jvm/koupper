package com.koupper.providers.rss

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class RSSServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(RSSReader::class, {
            RSSReaderImpl()
        })
    }
}
