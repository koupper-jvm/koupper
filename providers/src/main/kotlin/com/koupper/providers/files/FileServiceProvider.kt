package com.koupper.providers.files

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class FileServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerFileHandler()
        this.registerTextFileHandler()
        this.registerJsonFileHandler()
        this.registerYmlFileHandler()
    }

    private fun registerFileHandler() {
        app.bind(FileHandler::class, { FileHandlerImpl() })
    }

    private fun registerTextFileHandler() {
        app.bind(TextFileHandler::class, {
            TextFileHandlerImpl()
        })
    }

    private fun registerJsonFileHandler() {
        app.bind(JSONFileHandler::class, { JSONFileHandlerImpl() })
    }

    private fun registerYmlFileHandler() {
        app.bind(YmlFileHandler::class, {
            YmlFileHandlerImpl()
        })
    }

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "files" to """
            import com.koupper.providers.files.FileHandler
            fun files(): FileHandler = com.koupper.container.app.getInstance(FileHandler::class)
        """.trimIndent(),
        "json" to """
            import com.koupper.providers.files.JSONFileHandler
            fun json(): JSONFileHandler = com.koupper.container.app.getInstance(JSONFileHandler::class)
        """.trimIndent(),
        "yml" to """
            import com.koupper.providers.files.YmlFileHandler
            fun yml(): YmlFileHandler = com.koupper.container.app.getInstance(YmlFileHandler::class)
        """.trimIndent(),
        "txt" to """
            import com.koupper.providers.files.TextFileHandler
            fun txt(): TextFileHandler = com.koupper.container.app.getInstance(TextFileHandler::class)
        """.trimIndent()
    )
}
