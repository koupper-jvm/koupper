package com.koupper.octopus.modules.http.service

import com.koupper.container.app
import com.koupper.octopus.modifiers.GradleConfigurator
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.locateScriptsInPackage
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.downloadFile
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class GrizzlyGradleJerseyBuilder(private val projectName: String,
                                 private val moduleVersion: String,
                                 private val packageName: String,
                                 private val scripts: List<String>) : Module() {

    private val fileHandler = app.createInstanceOf(FileHandler::class)

    private constructor(builder: Builder):  this (
        builder.projectName,
        builder.version,
        builder.packageName,
        builder.deployableScripts
    )

    companion object {
        inline fun build(config: Builder.() -> Unit) = Builder().apply(config).build().build()
    }

    override fun build() {
        val modelProject = this.fileHandler.unzipFile(env("MODEL_BACK_PROJECT_URL"))

        File("${modelProject.name}.zip").delete()

        GradleConfigurator.configure {
            this.rootProjectName = projectName
            this.projectPath = modelProject.path
            this.version = moduleVersion
        }

        print("\u001B[38;5;155m\nRequesting an optimized process manager... \u001B[0m")

        File("$projectName/libs").mkdir()

        downloadFile(
            URL(env("OPTIMIZED_PROCESS_MANAGER_URL")),
            "$projectName/libs/octopus-${env("OCTOPUS_VERSION")}.jar"
        )

        println("\u001B[38;5;155m✔\u001B[0m")

        println("\u001B[38;5;155mOptimized process manager located successfully.\u001B[0m")

        locateScriptsInPackage(scripts, Paths.get(modelProject.name).absolutePathString(),this.packageName)

        Files.move(Paths.get(modelProject.name), Paths.get(projectName))
    }

    class Builder {
        var projectName : String = "undefined"
        var version : String = "0.0.0"
        var packageName: String = ""
        var deployableScripts = mutableListOf<String>()

        fun build() = GrizzlyGradleJerseyBuilder(this)
    }
}