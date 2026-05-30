package com.koupper.providers.memory

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.vectordb.VectorDbProvider
import java.io.File

class MemoryServiceProvider : ServiceProvider() {
    override fun up() {
        val storeDir = File(System.getProperty("user.home"), ".koupper/memory")
        app.bind(MemoryProvider::class, {
            val vectorDb = app.getInstance(VectorDbProvider::class)
            LocalMemoryProvider(vectorDb, storeDir)
        })
    }
}
