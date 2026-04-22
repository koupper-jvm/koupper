package com.koupper.octopus.modifiers

import com.koupper.container.app
import com.koupper.octopus.createDefaultConfiguration
import com.koupper.octopus.modules.aws.API
import com.koupper.octopus.modules.aws.loadAPIDefinitionFromConfiguration
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.files.YmlFileHandler

class SetupGrizzlyConfigurator private constructor(
    private val context: String,
    private val packageName: String,
    private val projectName: String,
) {
    private val textFileHandler = app.getInstance(TextFileHandler::class)
    private var serverPort = 8080

    constructor(builder: Builder) : this(
        builder.context,
        builder.packageName,
        builder.projectName
    )

    companion object {
        inline fun configure(block: Builder.() -> Unit) = Builder().apply(block).build().build()
    }

    fun build() {
        this.textFileHandler.using("$projectName/src/main/kotlin/server/Setup.kt")

        val rootLineNumber = this.textFileHandler.getNumberLineFor("const val PORT = 8080")

        this.textFileHandler.replaceLine(
            rootLineNumber,
            "const val PORT = $serverPort",
            overrideOriginal = true
        )
    }

    class Builder {
        var context: String = ""
        var packageName = "undefined"
        var projectName = "undefined"
        var version = "undefined"

        fun build() = SetupGrizzlyConfigurator(this)
    }
}
