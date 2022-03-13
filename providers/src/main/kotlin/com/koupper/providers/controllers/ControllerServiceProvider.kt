package com.koupper.providers.controllers

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class ControllerServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(RouteDefinition::class, {
            Route()
        })
    }
}