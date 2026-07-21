package com.koupper.providers.files

import com.koupper.container.app
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

class FileServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.CORE

    override fun up() {
        this.registerFileHandler()
        this.registerTextFileHandler()
        this.registerJsonFileHandler()
        this.registerYmlFileHandler()
        this.registerFileWatcherHandler()
        this.registerEditHandler()
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

    private fun registerFileWatcherHandler() {
        app.bind(FileWatcherProvider::class, { FileWatcherProviderImpl() })
    }

    private fun registerEditHandler() {
        app.bind(EditProvider::class, { EditProviderImpl() })
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
        """.trimIndent(),
        "fs" to """
            import com.koupper.providers.files.FileHandler
            fun fs(): FileHandler = com.koupper.container.app.getInstance(FileHandler::class)
        """.trimIndent(),
        "sse" to """
            import com.koupper.providers.runtime.router.SseEmitter
            fun sse(): SseEmitter = SseEmitter()
        """.trimIndent(),
        "validate" to """
            import com.koupper.shared.validators.core.*
            fun validate(map: Map<String, Any?>, required: List<String>): VResult<Map<String, Any?>> {
                val errors = required.mapNotNull { field ->
                    if (map[field] == null || map[field].toString().isBlank())
                        VError(field, listOf("required"))
                    else null
                }
                return if (errors.isEmpty()) VResult(true, map) else VResult(false, null, errors)
            }
        """.trimIndent(),
        "watcher" to """
            import com.koupper.providers.files.FileWatcherProvider
            fun watcher(): FileWatcherProvider = com.koupper.container.app.getInstance(FileWatcherProvider::class)
        """.trimIndent(),
        "edit" to """
            import com.koupper.providers.files.EditProvider
            fun edit(): EditProvider = com.koupper.container.app.getInstance(EditProvider::class)
        """.trimIndent()
    )
}
