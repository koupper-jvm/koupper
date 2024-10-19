package com.koupper.octopus.modules.aws

import com.koupper.container.app
import com.koupper.octopus.modifiers.*
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.http.Post
import com.koupper.octopus.modules.http.Put
import com.koupper.octopus.modules.http.Route
import com.koupper.octopus.modules.http.RouteDefinition
import com.koupper.octopus.modules.locateScriptsInPackage
import com.koupper.octopus.process.getRealScriptNameFrom
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

class LocalAWSDeploymentBuilder(
    private val projectName: String,
    private val moduleVersion: String,
    private val packageName: String,
    private val deployableScripts: Map<String, String>
) : Module() {

    private val fileHandler = app.createInstanceOf(FileHandler::class)
    private val txtFileHandler = app.createInstanceOf(TextFileHandler::class)
    private val routerMaker = Route(app)
    private lateinit var routeDefinition: RouteDefinition
    private lateinit var apiDefinition: Map<String, Any>
    private lateinit var modelProject: File

    private constructor(builder: Builder) : this(
        builder.projectName,
        builder.version,
        builder.packageName,
        builder.deployableScripts
    )

    companion object {
        inline fun build(config: Builder.() -> Unit) = Builder().apply(config).build().build()
    }

    init {
        this.apiDefinition =  this.loadAPIDefinitionFromConfiguration()
    }

    override fun build() {
        this.modelProject = this.fileHandler.unzipFile(env("MODEL_BACK_PROJECT_URL"))

        File("${modelProject.name}.zip").delete()

        GradleConfigurator.configure {
            this.rootProjectName = projectName
            this.projectPath = modelProject.path
            this.version = moduleVersion
        }

        print("\u001B[38;5;155m\nRequesting an optimized process manager... \u001B[0m")

        File("${this.projectName}/libs").mkdir()

        downloadFile(
            URL(env("OPTIMIZED_PROCESS_MANAGER_URL")),
            "${this.modelProject.name}/libs/octopus-${env("OCTOPUS_VERSION")}.jar"
        )

        println("\u001B[38;5;155m✔\u001B[0m")

        println("\u001B[38;5;155mOptimized process manager located successfully.\u001B[0m")

        locateScriptsInPackage(deployableScripts.values.toList(), this.modelProject.name, this.packageName)

        this.createRequestHandlers(deployableScripts, this.apiDefinition.getValue("handlers"))

        this.createGenericModelController(this.apiDefinition)

        Files.deleteIfExists(Paths.get("${this.projectName}/src/main/kotlin/controllers/ModelController.kt"))

        Files.move(Paths.get(this.modelProject.name), Paths.get(this.projectName))
    }

    private fun createGenericModelController(apiDefinition: Map<String, Any>) {
        this.routeDefinition = routerMaker.registerRouter {
            val paths = apiDefinition["paths"] as List<String>
            val methods = apiDefinition["methods"] as List<String>
            val handlers = apiDefinition["handlers"] as List<String>
            val descriptions = apiDefinition["descriptions"] as List<String>

            paths.forEachIndexed { index, path ->
                val p = paths[index]
                val m = methods[index]
                val h = handlers[index]
                val d = descriptions[index]

                path { "" }

                controllerName {
                    "AWSLambdaController"
                }

                if (m.equals("post", true)) {
                    post {
                        path { p.ifEmpty { throw IllegalArgumentException("Path for api can't be empty") } }
                        identifier { "postMethod" }
                        script { h }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

                if (m.equals("get", true)) {
                    get {
                        path { p.ifEmpty { throw IllegalArgumentException("Path for api can't be empty") } }
                        identifier { "getMethod" }
                        script { h }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

                if (m.equals("put", true)) {
                    put {
                        path { p.ifEmpty { throw IllegalArgumentException("Path for api can't be empty") } }
                        identifier { "putMethod" }
                        script { h }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

                if (m.equals("delete", true)) {
                    delete {
                        path { p.ifEmpty { throw IllegalArgumentException("Path for api can't be empty") } }
                        identifier { "deleteMethod" }
                        script { h }
                        produces { listOf("application/json") }
                        consumes { listOf("application/json") }
                    }
                }

            }
        }

        val self = this

        RequestHandlerControllerBuilder.build(this.modelProject.name) {
            this.path = routeDefinition.path()

            this.controllerConsumes = routeDefinition.consumes()

            this.controllerProduces = routeDefinition.produces()

            this.controllerName = routeDefinition.controllerName()

            this.packageName = self.packageName

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

        Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/RequestHandler.kt"), Paths.get("${this.modelProject.name}/src/main/kotlin/server/${routeDefinition.controllerName()}"))
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

    private fun createRequestHandlers(deployableScripts: Map<String, String>, handlers: Any) {
        val handlersList = handlers as List<String>

        handlersList.forEachIndexed { _, handler ->
            if (deployableScripts[handler] == null) {
                return@forEachIndexed
            }

            val destinationFile =
                Paths.get("${this.modelProject.name}/src/main/kotlin/server/RequestHandler${handler.replaceFirstChar { it.uppercaseChar() }}.kt")

            if (destinationFile.exists()) {
                return@forEachIndexed
            }

            Files.copy(Paths.get("${this.modelProject.name}/src/main/kotlin/server/RequestHandler.kt"), destinationFile)

            val script = deployableScripts[handler]

            val lastSlash = script!!.lastIndexOf('/')

            val lastBackslash = script.lastIndexOf('\\')

            val lastIndexOfPath = maxOf(lastSlash, lastBackslash)

            val scriptPath = script.substring(0, lastIndexOfPath + 1)

            val scriptPackage = scriptPath.replace(Regex("""(?<!\\)[/\\](?!\\)"""), ".")

            val scriptNameWithoutExtension = script.substring(lastIndexOfPath + 1).substring(0, script.lastIndexOf(".") - 1)

            val finalNameWhitHyphen = this.getFunctionFinalName(getRealScriptNameFrom(scriptNameWithoutExtension), "-")

            val finalNameWhitDash = this.getFunctionFinalName(getRealScriptNameFrom(scriptNameWithoutExtension), "_")

            val finalScriptName: String = if (finalNameWhitHyphen.isNotEmpty()) {
                finalNameWhitHyphen.substringBeforeLast(".")
            } else if (finalNameWhitDash.isNotEmpty()) {
                finalNameWhitDash.substringBeforeLast(".")
            } else {
                throw Exception("Script file can not be empty.")
            }

            val fileContent = this.txtFileHandler.using(destinationFile.pathString)

            val importStatement = "import io.mp.extensions.functionName"

            fileContent.replaceLine(
                this.txtFileHandler.getNumberLineFor(importStatement),
                importStatement.replace(importStatement, "import ${this.packageName}.extensions.$scriptPackage$finalScriptName"),
                overrideOriginal = true
            )

            val executorSentence = "executor.call(functionName, input)"

            fileContent.replaceLine(
                this.txtFileHandler.getNumberLineFor(executorSentence),
                "${String.format("%-12s", " ")}$executorSentence".replace("functionName", finalScriptName),
                overrideOriginal = true
            )
        }

        val sourceFile = Paths.get("$projectName/src/main/kotlin/server/RequestHandler.kt")
        Files.deleteIfExists(sourceFile)
    }

    private fun loadAPIDefinitionFromConfiguration(): Map<String, Any> {
        val ymlHandler = app.createInstanceOf(YmlFileHandler::class)

        val content = ymlHandler.readFrom(env("CONFIG_DEPLOYMENT_FILE"))

        val apis = content["apis"] as? List<Map<String, String>>

        require(!apis.isNullOrEmpty()) { "A configuration yml file for deployment is expected." }

        val apiDefinition: MutableMap<String, List<String>> = mutableMapOf()

        val paths: MutableList<String> = mutableListOf()
        val methods: MutableList<String> = mutableListOf()
        val handlers: MutableList<String> = mutableListOf()
        val descriptions: MutableList<String> = mutableListOf()

        for (api in apis) {
            val path = api["path"]
            require(!path.isNullOrEmpty()) { "El campo 'path' está vacío o no existe en una de las entradas de 'apis'." }
            paths.add(path)

            val method = api["method"]
            require(!(method.isNullOrEmpty())) { "El campo 'methods' está vacío o no existe en una de las entradas de 'apis'." }
            methods.add(method)

            val handler = api["handler"]
            require(!(handler.isNullOrEmpty())) { "El campo 'handlers' está vacío o no existe en una de las entradas de 'apis'." }
            handlers.add(handler)

            val description = api["description"]
            require(!(description.isNullOrEmpty())) { "El campo 'description' está vacío o no existe en una de las entradas de 'apis'." }
            descriptions.add(description)
        }

        apiDefinition["paths"] = paths
        apiDefinition["methods"] = methods
        apiDefinition["handlers"] = handlers
        apiDefinition["descriptions"] = descriptions

        return apiDefinition
    }

    class Builder {
        var projectName: String = "undefined"
        var version: String = "0.0.0"
        var packageName: String = ""
        var deployableScripts = mapOf<String, String>()

        fun build() = LocalAWSDeploymentBuilder(this)
    }
}