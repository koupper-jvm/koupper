package com.koupper.providers.parsing

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class TextJsonParserServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerJsonToObject()
    }

    private fun registerJsonToObject() {
        app.bind(TextJsonParser::class, {
            JsonToObject<Any>()
        })
    }
}
