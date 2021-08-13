package com.koupper.providers.files

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class FileServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerFileHandler()
        this.registerTextFileHandler()
    }

    private fun registerFileHandler() {
        app.bind(FileHandler::class, { FileHandlerImpl() })
    }

    private fun registerTextFileHandler() {
        app.bind(TextFileHandler::class, {
            TextFileHandlerImpl(it)
        })
    }
}
