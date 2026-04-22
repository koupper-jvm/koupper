package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.providers.files.TextFileHandler
import java.io.File

class GradleConfigurator private constructor(
    private val rootProjectName: String,
    private val version: String,
    private val packageName: String,
    private val projectRootPath: String,
) {
    private var textFileHandler: TextFileHandler = app.getInstance(TextFileHandler::class)

    private constructor(builder: Builder) : this(
        builder.rootProjectName,
        builder.version,
        builder.packageName,
        builder.projectRootPath,
    )

    companion object {
        inline fun configure(block: Builder.() -> Unit) =
            Builder().apply(block).build().build()
    }

    fun build() {
        this.setName()
        this.setVersion()
    }

    private fun setName() {
        this.textFileHandler.using(resolveProjectFilePath("settings.gradle"))
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("rootProject.name = 'model-project'"),
            "rootProject.name = '${this.rootProjectName}'",
            overrideOriginal = true
        )
    }

    private fun setVersion() {
        this.textFileHandler.using(resolveProjectFilePath("build.gradle"))
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("version = '0.0.0'"),
            "version = '${this.version}'",
            overrideOriginal = true
        )
    }

    private fun resolveProjectFilePath(fileName: String): String {
        if (projectRootPath.isNotBlank()) {
            return File(projectRootPath, fileName).absolutePath
        }

        return File(rootProjectName, fileName).path
    }

    class Builder() {
        var rootProjectName: String = "undefined"
        var version: String = "0.0.0"
        var packageName: String = ""
        var projectRootPath: String = ""

        fun build() = GradleConfigurator(this)
    }
}
