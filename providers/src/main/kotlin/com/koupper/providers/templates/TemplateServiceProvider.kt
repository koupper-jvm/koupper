package com.koupper.providers.templates

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class TemplateServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerTemplateProvider()
    }

    private fun registerTemplateProvider() {
        app.bind(TemplateProvider::class, {
            PebbleTemplateProvider()
        })
    }
}
