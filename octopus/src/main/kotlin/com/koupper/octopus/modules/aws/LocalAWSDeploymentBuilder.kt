package com.koupper.octopus.modules.aws

import com.koupper.container.app
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

class LocalAWSDeploymentBuilder(
    private val projectName: String,
    private val moduleVersion: String,
    private val packageName: String,
    private val deployableScripts: Map<String, String>,
    private val rootPath: String
) : Module() {

    private val fileHandler = app.createInstanceOf(FileHandler::class)
    private val txtFileHandler = app.createInstanceOf(TextFileHandler::class)
    private val routerMaker = Route(app)
    private lateinit var routeDefinition: RouteDefinition
    private var apiDefinition: List<API>
    private lateinit var modelProject: File

    private constructor(builder: Builder) : this(
        builder.projectName,
        builder.version,
        builder.packageName,
        builder.deployableScripts,
        builder.rootPath
    )

    companion object {
        inline fun build(config: Builder.() -> Unit) = Builder().apply(config).build().build()
    }

    init {
        this.apiDefinition =  this.loadAPIDefinitionFromConfiguration()
    }

    override fun build() {
        print("\u001B[38;5;155m\nBuilding module... \u001B[0m")
        this.modelProject = this.fileHandler.unzipFile(env("MODEL_BACK_PROJECT_URL"))

        File("${modelProject.name}.zip").delete()

        GradleConfigurator.configure {
            this.rootProjectName = projectName
            this.projectPath = modelProject.path
            this.version = moduleVersion
        }

        println("\u001B[38;5;155m✔\u001B[0m")

        print("\u001B[38;5;155m\nRequesting an optimized process manager... \u001B[0m")

        File("${this.projectName}/libs").mkdir()

        downloadFile(
            URL(env("OPTIMIZED_PROCESS_MANAGER_URL")),
            "${this.modelProject.name}/libs/octopus-${env("OCTOPUS_VERSION")}.jar"
        )

        println("\u001B[38;5;155m✔\u001B[0m")

        println("\u001B[38;5;155mOptimized process manager located successfully.\u001B[0m")

        locateScriptsInPackage(deployableScripts, this.modelProject.name, this.packageName)

        this.createRequestHandlers()

        this.buildRequestHandlerController()

        this.locateBootstrappingFile()

        File("${this.modelProject.name}/src/main/kotlin/io/mp").apply { walkBottomUp().forEach { it.delete() } }

        Files.move(Paths.get(this.modelProject.name), Paths.get(this.projectName))
    }

    private fun locateBootstrappingFile() {
        Files.move(Paths.get("${this.modelProject.name}/src/main/kotlin/io/mp/Bootstrapping.kt"), Paths.get("${this.modelProject.name}/src/main/kotlin/${packageName.replace(".", "/")}/extensions/Bootstrapping.kt"))

        val fileContent = this.txtFileHandler.using("${this.modelProject.name}/src/main/kotlin/${packageName.replace(".", "/")}/extensions/Bootstrapping.kt")

        fileContent.replaceLine(
            this.txtFileHandler.getNumberLineFor("package io.mp.extensions"),
            "package ${packageName}.extensions",
            overrideOriginal = true
        )
    }

    private fun buildRequestHandlerController() {
        this.routeDefinition = routerMaker.registerRouter {
            apiDefinition.forEach { api ->
                path { rootPath }

                controllerName {
                    "AWSLambdaController"
                }

                val apiPath = api.path.ifEmpty { throw IllegalArgumentException("Path for api can't be empty") }

                if (api.method.equals("post", true)) {
                    post {
                        path { apiPath }
                        identifier { api.handler }
                        script { api.handler }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

                if (api.method.equals("get", true)) {
                    get {
                        path { apiPath }
                        identifier { api.handler }
                        script { api.handler }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

                if (api.method.equals("put", true)) {
                    put {
                        path { apiPath }
                        identifier { api.handler }
                        script { api.handler }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

                if (api.method.equals("delete", true)) {
                    delete {
                        path { apiPath }
                        identifier { api.handler }
                        script { api.handler }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

            }
        }

        val self = this

        RequestHandlerControllerBuilder.build {
            this.path = routeDefinition.path()

            this.controllerConsumes = routeDefinition.consumes()

            this.controllerProduces = routeDefinition.produces()

            this.controllerName = routeDefinition.controllerName()

            this.packageName = self.packageName

            this.registeredScripts = self.registeredScripts

            val pm = generateMethods(Action.POST, self.routeDefinition.postMethods()) {
                (it as Post).body
            }

            if (pm.isNotEmpty()) {
                this.methods.addAll(pm)
            }

            val gm = generateMethods(Action.GET, self.routeDefinition.getMethods()) {
                null
            }

            if (gm.isNotEmpty()) {
                this.methods.addAll(gm)
            }

            val pum = generateMethods(Action.PUT, self.routeDefinition.putMethods()) {
                (it as Put).body
            }

            if (pum.isNotEmpty()) {
                this.methods.addAll(pum)
            }

            val dm = generateMethods(Action.DELETE, self.routeDefinition.deleteMethods()) {
                null
            }

            if (dm.isNotEmpty()) {
                this.methods.addAll(dm)
            }
        }
    }

    private fun generateMethods(
        action: Action,
        routeList: MutableList<RouteDefinition>,
        bodyProvider: (RouteDefinition) -> KClass<*>?
    ): List<Method> {
        return routeList.map { route ->
            Method(
                route.identifier(),
                action,
                route.path(),
                route.consumes(),
                route.produces(),
                route.queryParams(),
                route.matrixParams(),
                route.headerParams(),
                route.cookieParams(),
                route.formParams(),
                route.response(),
                route.script(),
                bodyProvider(route)
            )
        }
    }

    private fun createRequestHandlers() {
        apiDefinition.forEach { api ->
            val destinationFile =
                Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler${api.handler.replaceFirstChar { it.uppercaseChar() }}.kt")

            if (destinationFile.exists()) {
                return@forEach
           }

            val finalRequestHandler = this.txtFileHandler.using(destinationFile.pathString)

            val scriptReturnType = this.registeredScripts[api.handler]?.second ?: ""

            val scriptPackage = super.registeredScriptPackages[api.handler]

            val scriptName = this.deployableScripts[api.handler]!!

            var requestHandlerName = ""

            if (scriptReturnType == "Unit") {
                Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler204.kt"), destinationFile)
                requestHandlerName = "RequestHandler204"
            } else if (isPrimitive(scriptReturnType)) {
                Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler200.kt"), destinationFile)
                requestHandlerName = "RequestHandler200"

                val executorSentence = "${String.format("%-8s", " ")}val result: Any = executor.call(functionName, mapOf("

                finalRequestHandler.replaceLine(
                    this.txtFileHandler.getNumberLineFor(executorSentence),
                    executorSentence.replace("Any", scriptReturnType).replace("functionName", this.buildScriptName(scriptName)),
                    overrideOriginal = true
                )

                if (scriptReturnType == "String") {
                    val bodySentence = "${String.format("%-12s", " ")}body = result.toString()"

                    finalRequestHandler.replaceLine(
                        this.txtFileHandler.getNumberLineFor(bodySentence),
                        bodySentence.replace("result.toString()", "result"),
                        overrideOriginal = true
                    )
                }
            } else {
                Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/handlers/RequestHandler200S.kt"), destinationFile)
                requestHandlerName = "RequestHandler200S"

                val executorSentence = "${String.format("%-8s", " ")}val result: Any = executor.call(functionName, mapOf("

                finalRequestHandler.replaceLine(
                    this.txtFileHandler.getNumberLineFor(executorSentence),
                    executorSentence.replace("Any", scriptReturnType),
                    overrideOriginal = true
                )
            }

            val importStatement = "import io.mp.extensions.functionName"

            finalRequestHandler.replaceLine(
                this.txtFileHandler.getNumberLineFor(importStatement),
                importStatement.replace(importStatement, "import ${this.packageName}.extensions.$scriptPackage${this.buildScriptName(scriptName)}"),
                overrideOriginal = true
            )

            val partClassDeclaration = "class $requestHandlerName"

            val classDeclarationNumberLine= this.txtFileHandler.getNumberLineFor(partClassDeclaration)

            val completeClassDeclaration = this.txtFileHandler.getContentForLine(classDeclarationNumberLine)

            finalRequestHandler.replaceLine(
                classDeclarationNumberLine,
                completeClassDeclaration.replace(partClassDeclaration, "class RequestHandler${api.handler.replaceFirstChar { it.uppercaseChar() }}"),
                overrideOriginal = true
            )
        }

        Files.deleteIfExists(Paths.get("model-project/src/main/kotlin/server/handlers/RequestHandler200.kt"))
        Files.deleteIfExists(Paths.get("model-project/src/main/kotlin/server/handlers/RequestHandler200S.kt"))
        Files.deleteIfExists(Paths.get("model-project/src/main/kotlin/server/handlers/RequestHandler204.kt"))
    }

    private fun loadAPIDefinitionFromConfiguration(): List<API> {
        val ymlHandler = app.createInstanceOf(YmlFileHandler::class)

        val content = ymlHandler.readFrom(env("CONFIG_DEPLOYMENT_FILE"))

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

    class Builder {
        var projectName: String = "undefined"
        var version: String = "0.0.0"
        var packageName: String = ""
        var deployableScripts = mapOf<String, String>()
        var rootPath = "/"

        fun build() = LocalAWSDeploymentBuilder(this)
    }
}