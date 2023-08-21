package com.koupper.octopus.routes

import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.ModuleMaker
import com.koupper.octopus.process.deployment.GradleDeployerJarFile
import com.koupper.providers.files.TextFileHandler
import java.io.BufferedReader
import java.io.InputStreamReader

class DeploymentBuilder private constructor(
    private var location: String,
    var container: Container,
    var port: Int,
    var rootUrl: String,
    var packageName: String,
    var projectName: String,
    var version: String = "undefined",
) {
    private val textFileHandler = this.container.createInstanceOf(TextFileHandler::class)

    constructor(builder: Builder) : this(
        builder.location,
        builder.container,
        builder.port,
        builder.rootUrl,
        builder.projectName,
        builder.packageName,
        builder.version
    )

    companion object {
        inline fun build(location: String, container: Container, block: Builder.() -> Unit) =
            Builder(location, container).apply(block).build()
    }

    fun build() {
        val packageLineNumber = this.textFileHandler.getNumberLineFor(
            "packages(\"io.mp\")",
            "$projectName/src/main/kotlin/server/Setup.kt"
        )

        this.textFileHandler.replaceLine(
            packageLineNumber,
            "${String.format("%-8s", " ")}packages(\"${this.packageName}\")",
            "$projectName/src/main/kotlin/server/Setup.kt",
            true
        )

        val rootLineNumber =
            this.textFileHandler.getNumberLineFor("root", "$projectName/src/main/kotlin/server/Setup.kt")

        this.textFileHandler.replaceLine(
            rootLineNumber,
            "${String.format("%-4s", " ")}val url = UriBuilder.fromUri(\"http://localhost/${this.rootUrl}\")",
            "${this.projectName}/src/main/kotlin/server/Setup.kt",
            true
        )

        val portLineNumber =
            this.textFileHandler.getNumberLineFor(".port(8080)", "${this.projectName}/src/main/kotlin/server/Setup.kt")

        this.textFileHandler.replaceLine(
            portLineNumber,
            "${String.format("%-8s", " ")}.port(${this.port})",
            "${this.projectName}/src/main/kotlin/server/Setup.kt",
            true
        )

        GradleDeployerJarFile(this.container).register(
            this.projectName,
            mapOf(
                "moduleType" to "GRADLE_DEPLOYER",
                "location" to this.location,
                "version" to this.version,
            )
        ).run()
    }

    class Builder(var location: String, var container: Container) {
        var port = 8080
        var rootUrl = "/"
        var packageName = "undefined"
        var projectName = "undefined"
        var version = "undefined"

        fun build() = DeploymentBuilder(this)
    }
}
