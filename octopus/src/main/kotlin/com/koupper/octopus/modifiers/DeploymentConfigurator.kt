package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.providers.files.TextFileHandler

class DeploymentConfigurator private constructor(
    private val port: Int,
    private val rootUrl: String,
    private val packageName: String,
    private val projectName: String,
    private val version: String
) {
    private val textFileHandler = app.createInstanceOf(TextFileHandler::class)

    constructor(builder: Builder) : this(
        builder.port,
        builder.rootUrl,
        builder.projectName,
        builder.packageName,
        builder.version
    )

    companion object {
        inline fun configure(block: Builder.() -> Unit) = Builder().apply(block).build().build()
    }

    fun build() {
        this.textFileHandler.using("$projectName/src/main/kotlin/server/Setup.kt")

        val rootLineNumber = this.textFileHandler.getNumberLineFor("const val PORT = 8080")

        this.textFileHandler.replaceLine(
            rootLineNumber,
            "const val PORT = $port",
            overrideOriginal = true
        )

        val packageLineNumber = this.textFileHandler.getNumberLineFor("packages(\"io.mp\")")

        this.textFileHandler.replaceLine(
            packageLineNumber,
            "${String.format("%-8s", " ")}packages(\"${this.packageName}\")",
            overrideOriginal = true
        )

        val portLineNumber = this.textFileHandler.getNumberLineFor(".path(\"root\")")

        this.textFileHandler.replaceLine(
            portLineNumber,
            "${String.format("%-8s", " ")}.path(${this.port})",
            overrideOriginal = true
        )
    }

    class Builder {
        var port = 8080
        var rootUrl = "/"
        var packageName = "undefined"
        var projectName = "undefined"
        var version = "undefined"

        fun build() = DeploymentConfigurator(this)
    }
}
