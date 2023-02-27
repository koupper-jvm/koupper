package com.koupper.octopus.routes

import com.koupper.container.interfaces.Container
import java.io.BufferedReader
import java.io.InputStreamReader

class DeploymentBuilder private constructor(
    private var location: String,
    var container: Container,
    var port: Int,
    var rootUrl: String
){

    constructor(builder: Builder) : this(
        builder.location,
        builder.container,
        builder.port,
        builder.rootUrl
    )

    companion object {
        inline fun build(location: String, container: Container, block: Builder.() -> Unit) =
            Builder(location, container).apply(block).build()
    }

    fun build() {
        /*val processBuilder = ProcessBuilder("${location}/gradlew", "build")
        val process = processBuilder.start()

        val inputStream = process.inputStream
        val inputReader = BufferedReader(InputStreamReader(inputStream))

        inputReader.forEachLine { line ->
            println(line)
        }

        val exitCode = process.waitFor()

        println("Process finished with exit code $exitCode")*/
    }

    class Builder(var location: String, var container: Container) {
        var port = 8080
        var rootUrl = "http://localhost"

        fun build() = DeploymentBuilder(this)
    }
}
