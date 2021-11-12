package com.koupper.providers.jwt

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class JWTServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(JWT::class, {
            JWTAgent(it)
        })
    }
}
