package com.koupper.providers.crypto

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class CryptoServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(Crypt0::class, {
            AESGCM128(it)
        })
    }
}
