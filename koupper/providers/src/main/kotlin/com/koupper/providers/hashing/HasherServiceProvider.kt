package com.koupper.providers.hashing

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class HasherServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(Hasher::class, { PBKDF2Hasher() })
    }
}