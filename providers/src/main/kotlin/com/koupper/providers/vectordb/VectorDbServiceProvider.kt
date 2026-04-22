package com.koupper.providers.vectordb

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class VectorDbServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(VectorDbProvider::class, {
            LocalVectorDbProvider()
        })
    }
}
