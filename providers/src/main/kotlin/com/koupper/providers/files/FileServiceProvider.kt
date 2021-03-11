package com.koupper.providers.files

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class FileServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerFile()
    }

    private fun registerFile() {
        app.bind(FileHandler::class, { FileHandlerImpl() })
    }
}
