package com.koupper.providers.vectordb

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import java.io.File

class VectorDbServiceProvider : ServiceProvider() {
    override fun up() {
        val dataDir = File(System.getProperty("user.home"), ".koupper/vectordb")
        app.bind(VectorDbProvider::class, {
            LocalVectorDbProvider(dataDir)
        })
    }
}
