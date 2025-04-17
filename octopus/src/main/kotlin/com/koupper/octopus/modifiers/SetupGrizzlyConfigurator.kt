package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.providers.files.TextFileHandler

class SetupGrizzlyConfigurator private constructor(
    private val port: Int,
    private val rootUrl: String,
    private val packageName: String,
    private val projectName: String,
    private val version: String
) {
    private val textFileHandler = app.getInstance(TextFileHandler::class)

    constructor(builder: Builder) : this(
        builder.port,
        builder.rootUrl,
        builder.packageName,
        builder.projectName,
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

        val packageLineNumber = this.textFileHandler.getNumberLineFor("packages(\"io.mp.controllers\")")

        this.textFileHandler.replaceLine(
            packageLineNumber,
            "${String.format("%-8s", " ")}packages(\"${this.packageName}.controllers\")",
            overrideOriginal = true
        )
    }

    class Builder {
        var port = 8080
        var rootUrl = "/"
        var packageName = "undefined"
        var projectName = "undefined"
        var version = "undefined"

        fun build() = SetupGrizzlyConfigurator(this)
    }
}
