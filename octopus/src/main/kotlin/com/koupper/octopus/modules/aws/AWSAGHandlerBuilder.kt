package com.koupper.octopus.modules.aws

import com.koupper.configurations.utilities.ANSIColors
import com.koupper.container.app
import com.koupper.octopus.exceptions.UndefinedHandlerException
import com.koupper.octopus.modifiers.*
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.http.Post
import com.koupper.octopus.modules.http.Put
import com.koupper.octopus.modules.http.Route
import com.koupper.octopus.modules.http.RouteDefinition
import com.koupper.octopus.modules.isPrimitive
import com.koupper.octopus.modules.locateScriptsInPackage
import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.files.YmlFileHandler
import com.koupper.providers.files.downloadFile
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.reflect.KClass

data class API(val path: String, val method: String, val handler: String, val description: String)

fun loadAPIDefinitionFromConfiguration(context: String, projectName: String): List<API> {
    val ymlHandler = app.getInstance(YmlFileHandler::class)

    val content = ymlHandler.readFrom(context + File.separator + "${projectName}.yml")

    val apis = content["apis"] as? List<Map<String, String>>

    require(!apis.isNullOrEmpty()) { "A configuration yml file for deployment is expected." }

    val apisDefinitions = mutableListOf<API>()

    for (api in apis) {
        var path = ""
        var method = ""
        var handler = ""
        var description = ""

        val declaredPath = api["path"]
        require(!declaredPath.isNullOrEmpty()) { "El campo 'path' está vacío o no existe en una de las entradas de 'apis'." }
        path = declaredPath

        val declaredMethod = api["method"]
        require(!(declaredMethod.isNullOrEmpty())) { "El campo 'methods' está vacío o no existe en una de las entradas de 'apis'." }
        method = declaredMethod

        val declaredHandler = api["handler"]
        require(!(declaredHandler.isNullOrEmpty())) { "El campo 'handlers' está vacío o no existe en una de las entradas de 'apis'." }
        handler = declaredHandler

        val declaredDescription = api["description"]
        require(!(declaredDescription.isNullOrEmpty())) { "El campo 'description' está vacío o no existe en una de las entradas de 'apis'." }
        description = declaredDescription

        apisDefinitions.add(API(path, method, handler, description))
    }

    return apisDefinitions
}

class AWSAGHandlerBuilder(
    private val context: String,
    private val projectName: String,
    private val moduleVersion: String,
    private val packageName: String,
    private val deployableScripts: Map<String, String>
) : Module() {

    private val fileHandler = app.getInstance(FileHandler::class)
    private val txtFileHandler = app.getInstance(TextFileHandler::class)
    private lateinit var modelProject: File
    private var apiDefinition: List<API> = loadAPIDefinitionFromConfiguration(context, projectName)


    private constructor(builder: Builder) : this(
        builder.context,
        builder.projectName,
        builder.version,
        builder.packageName,
        builder.deployableScripts
    )

    companion object {
        inline fun build(config: Builder.() -> Unit): AWSAGHandlerBuilder {
            val builder = Builder().apply(config)
            val instance = builder.build()
            instance.build()
            return instance
        }
    }

    override fun build() {
        print("Building module...")

        this.modelProject = this.fileHandler.unzipFile(env("MODEL_BACK_PROJECT_URL", context), projectName)

        GradleConfigurator.configure {
            this.rootProjectName = projectName
            this.version = moduleVersion
        }

        print("\nRequesting an optimized process manager...")

        File("${this.modelProject.path}/libs").mkdir()

        downloadFile(
            URL(env("OPTIMIZED_PROCESS_MANAGER_URL", context)),
            "${modelProject.path}/libs/octopus-${env("OCTOPUS_VERSION", context)}.jar"
        )

        println("${ANSIColors.ANSI_GREEN_155}[\u2713]${ANSIColors.ANSI_RESET}")

        print("optimized process manager located successfully...")

        println("${ANSIColors.ANSI_GREEN_155}[\u2713]${ANSIColors.ANSI_RESET}")

        locateScriptsInPackage(context, deployableScripts, modelProject.path, this.packageName)

        this.createRequestHandlers()

        //this.buildRequestHandlerController()

        //this.locateBootstrappingFile()

        /*File("${this.modelProject.name}/src/main/kotlin/io").apply {
            walkBottomUp().forEach { it.delete() }
        }*/
    }

    /*private fun locateBootstrappingFile() {
        Files.move(Paths.get("${this.modelProject.name}/src/main/kotlin/io/mp/Bootstrapping.kt"), Paths.get("${this.modelProject.name}/src/main/kotlin/${packageName.replace(".", "/")}/extensions/Bootstrapping.kt"))

        val fileContent = this.txtFileHandler.using("${this.modelProject.name}/src/main/kotlin/${packageName.replace(".", "/")}/extensions/Bootstrapping.kt")

        fileContent.replaceLine(
            this.txtFileHandler.getNumberLineFor("package io.mp.extensions"),
            "package ${packageName}.extensions",
            overrideOriginal = true
        )
    }*/

    private fun createRequestHandlers() {
        apiDefinition.forEach { api ->
            val destinationFile =
                Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler${api.handler.replaceFirstChar { it.uppercaseChar() }}.kt")

            if (destinationFile.exists()) {
                return@forEach
           }

            val finalRequestHandler = this.txtFileHandler.using(destinationFile.pathString)

            val scriptInputParams: List<String> = this.registeredScripts[api.handler]?.first ?: emptyList()

            val scriptReturnType = this.registeredScripts[api.handler]?.second ?: ""

            val scriptPackage = super.registeredScriptPackages[api.handler]

            if (scriptReturnType == "Unit") {
                if (scriptInputParams.isNotEmpty()) {
                    Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler10.kt"), destinationFile)
                } else {
                    Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler00.kt"), destinationFile)
                }
            } else if (isPrimitive(scriptReturnType)) {
                if (scriptInputParams.isNotEmpty()) {
                    Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler1P.kt"), destinationFile)
                } else {
                    Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler0P.kt"), destinationFile)
                }
            } else {
                if (scriptInputParams.isNotEmpty()) {
                    Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler11.kt"), destinationFile)
                } else {
                    Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler01.kt"), destinationFile)
                }
            }

            finalRequestHandler.replacePlaceholders(mapOf(
                "import io.mp.extensions.{{FUNCTION_NAME}}" to "import ${this.packageName}.extensions.$scriptPackage${this.registeredFunctionNames[api.handler]}",
                "{{FUNCTION_NAME}}" to this.registeredFunctionNames[api.handler],
                "{{REQUEST_HANDLER_NAME}}" to "RequestHandler${api.handler.replaceFirstChar { it.uppercaseChar() }}",
                "{{TYPE}}" to scriptReturnType
            ), overrideOriginal = true)
        }

        val completeDeletion = File("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler10.kt").delete() &&
                File("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler00.kt").delete() &&
                File("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler1P.kt").delete() &&
                File("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler0P.kt").delete() &&
                File("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler11.kt").delete() &&
                File("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler01.kt").delete()

        if (completeDeletion) println("Handlers created ${ANSIColors.ANSI_GREEN_155}[✓]${ANSIColors.ANSI_RESET}")
    }

    class Builder {
        var context: String = ""
        var projectName: String = "undefined"
        var version: String = "0.0.0"
        var packageName: String = ""
        var deployableScripts = mapOf<String, String>()

        fun build() = AWSAGHandlerBuilder(this)
    }
}