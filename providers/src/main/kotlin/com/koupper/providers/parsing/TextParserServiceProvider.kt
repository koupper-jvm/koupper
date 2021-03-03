package com.koupper.providers.parsing

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class TextParserServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerTextParser()
    }

    private fun registerTextParser() {
        app.bind(TextParser::class, {
            TextReader()
        })
    }
}
