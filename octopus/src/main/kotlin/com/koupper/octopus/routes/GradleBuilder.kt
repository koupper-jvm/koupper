package com.koupper.octopus.routes

import com.koupper.container.interfaces.Container
import com.koupper.providers.files.TextFileHandler

class GradleBuilder private constructor(
    private val name: String,
    private val location: String,
    private val container: Container,
    private val version: String,
) {
    private var textFileHandler: TextFileHandler = this.container.createInstanceOf(TextFileHandler::class)

    private constructor(builder: Builder) : this(
        builder.name,
        builder.location,
        builder.container,
        builder.version
    )

    companion object {
        inline fun build(name: String, location: String, container: Container, block: Builder.() -> Unit) =
            Builder(name, location, container).apply(block).build()
    }

    fun build() {
        this.setName()
        this.setVersion()
    }

    private fun setName() {
        this.textFileHandler.using("${this.location}/settings.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("rootProject.name = 'model-project'"),
            "rootProject.name = '${this.name}'",
            overrideOriginal = true
        )
    }

    private fun setVersion() {
        this.textFileHandler.using("${this.location}/build.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("version = '{VERSION}'"),
            "version = '${this.version}'",
            overrideOriginal = true
        )
    }

    class Builder(val name: String, val location: String, val container: Container) {
        var version: String = "1.0.0"

        fun build() = GradleBuilder(this)
    }
}