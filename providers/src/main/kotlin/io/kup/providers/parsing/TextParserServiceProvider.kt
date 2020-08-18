package io.kup.providers.parsing

import io.kup.container.app
import io.kup.providers.ServiceProvider

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
