package com.koupper.providers.runtime.router

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class RuntimeRouterServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(RuntimeRouterProvider::class, {
            JdkRuntimeRouterProvider()
        })
    }
}
