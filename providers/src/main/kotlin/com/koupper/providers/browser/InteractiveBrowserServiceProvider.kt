package com.koupper.providers.browser

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class InteractiveBrowserServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(InteractiveBrowserProvider::class, { PlaywrightInteractiveBrowser() })
    }

    override fun externalDependencies() = listOf("com.microsoft.playwright:playwright:1.44.0")
}
