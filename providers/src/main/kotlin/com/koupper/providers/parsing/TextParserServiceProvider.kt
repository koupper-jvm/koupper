package com.koupper.providers.parsing

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class TextParserServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerHtmlEmailTemplate()
    }

    private fun registerHtmlEmailTemplate() {
        app.bind(TextParser::class, {
            TextParserHtmlEmailTemplate()
        }, "TextParserHtmlEmailTemplate")

        app.bind(TextParser::class, {
            TextParserEnvPropertiesTemplate()
        }, "TextParserEnvPropertiesTemplate")
    }
}
