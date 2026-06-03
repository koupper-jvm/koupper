package com.koupper.octopus.modules.aws

import com.koupper.container.app
import com.koupper.logging.GlobalLogger
import com.koupper.octopus.modifiers.GradleConfigurator
import com.koupper.octopus.modules.prepareTemplateProject
import com.koupper.octopus.modules.resolveAndCopyProcessManagerJar
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.locateScriptsInPackage
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class LambdaFunctionBuilder(
    private val context: String,
    private val projectName: String,
    private val moduleVersion: String,
    private val packageName: String,
    private val scripts: Map<String, String>) : Module() {

    private val fileHandler = app.getInstance(FileHandler::class)

    private constructor(builder: Builder):  this (
        builder.context,
        builder.projectName,
        builder.version,
        builder.packageName,
        builder.deployableScripts
    )

    companion object {
        inline fun build(config: Builder.() -> Unit) = Builder().apply(config).build().build()
    }

    override fun build() {
        val modelProject = prepareTemplateProject(context, projectName, this.fileHandler)

        GradleConfigurator.configure {
            this.rootProjectName = projectName
            this.version = moduleVersion
            this.projectRootPath = modelProject.absolutePath
        }

        GlobalLogger.log.info { "Requesting an optimized process manager..." }

        val libsDir = File(modelProject, "libs")
        libsDir.mkdirs()
        val octopusVersion = env("OCTOPUS_VERSION", context, required = false, allowEmpty = true, default = "latest")
        resolveAndCopyProcessManagerJar(context, libsDir, "octopus-$octopusVersion.jar")

        GlobalLogger.log.info { "Optimized process manager located successfully." }

        locateScriptsInPackage(context, scripts, Paths.get(modelProject.absolutePath).absolutePathString(), this.packageName)
    }

    class Builder {
        var context: String = ""
        var projectName : String = "undefined"
        var version : String = "0.0.0"
        var packageName: String = ""
        var deployableScripts = mapOf<String, String>()

        fun build() = LambdaFunctionBuilder(this)
    }
}
