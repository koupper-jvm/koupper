package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.providers.files.TextFileHandler

class GradleConfigurator private constructor(
    private val rootProjectName: String,
    private val version: String,
    private val packageName: String,
) {
    private var textFileHandler: TextFileHandler = app.getInstance(TextFileHandler::class)

    private constructor(builder: Builder) : this(
        builder.rootProjectName,
        builder.version,
        builder.packageName,
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
        this.textFileHandler.using("$rootProjectName/settings.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("rootProject.name = 'model-project'"),
            "rootProject.name = '${this.rootProjectName}'",
            overrideOriginal = true
        )
    }

    private fun setVersion() {
        this.textFileHandler.using("$rootProjectName/build.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("version = '0.0.0'"),
            "version = '${this.version}'",
            overrideOriginal = true
        )
    }

    class Builder() {
        var rootProjectName: String = "undefined"
        var version: String = "0.0.0"
        var packageName: String = ""

        fun build() = GradleConfigurator(this)
    }
}