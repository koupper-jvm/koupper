package com.koupper.octopus.routes

import com.koupper.container.interfaces.Container
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.files.TextFileHandlerImpl

class GradleBuilder private constructor(
    private val location: String,
    private val container: Container,
    private val name: String,
    private val version: String,
) {
    private var textFileHandler: TextFileHandler = this.container.createInstanceOf(TextFileHandler::class)

    private constructor(builder: Builder) : this(
        builder.location,
        builder.container,
        builder.name,
        builder.version,
    )

    companion object {
        inline fun build(location: String, container: Container, block: Builder.() -> Unit) =
            Builder(location, container).apply(block).build()
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

    class Builder(val location: String, val container: Container) {
        var name: String = Property.UNDEFINED.name
        var version: String = "1.0.0"

        fun build() = GradleBuilder(this)
    }
}