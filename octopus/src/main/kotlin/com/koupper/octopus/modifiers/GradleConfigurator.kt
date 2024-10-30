package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.providers.files.TextFileHandler

class GradleConfigurator private constructor(
    private val rootProjectName: String,
    private val projectPath: String,
    private val version: String,
    private val packageName: String,
) {
    private var textFileHandler: TextFileHandler = app.createInstanceOf(TextFileHandler::class)

    private constructor(builder: Builder) : this(
        builder.rootProjectName,
        builder.projectPath,
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
        this.textFileHandler.using("${this.projectPath}/settings.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("rootProject.name = 'model-project'"),
            "rootProject.name = '${this.rootProjectName}'",
            overrideOriginal = true
        )
    }

    private fun setVersion() {
        this.textFileHandler.using("${this.projectPath}/build.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("version = '0.0.0'"),
            "version = '${this.version}'",
            overrideOriginal = true
        )
    }

    class Builder() {
        var rootProjectName: String = "undefined"
        var projectPath: String = ""
        var version: String = "0.0.0"
        var packageName: String = ""

        fun build() = GradleConfigurator(this)
    }
}