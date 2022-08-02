package com.koupper.providers.controllers

import com.koupper.os.env
import com.koupper.providers.files.TextFileHandlerImpl
import java.io.File
import kotlin.reflect.KClass

enum class Type {
    JERSEY,
    KTOR
}

sealed interface RouteDefinition {
    fun type(type: () -> Type)
    fun path(path: () -> String)
    fun name(name: () -> String)
    fun middlewares(middlewares: () -> List<String>)
    fun queryParams(queryParams: () -> Map<String, KClass<*>>)
    fun matrixParams(matrixParams: () -> Map<String, KClass<*>>)
    fun headerParams(headerParams: () -> Map<String, KClass<*>>)
    fun cookieParams(cookieParams: () -> Map<String, KClass<*>>)
    fun formParams(formParams: () -> Map<String, KClass<*>>)
    fun response(response: () -> KClass<*>)
    fun script(script: () -> String)
    fun controllerName(controllerName: () -> String)
    fun produces(produces: () -> List<String>)
    fun consumes(consumes: () -> List<String>)
    fun post(route: Post.() -> Unit)
    fun get(route: Get.() -> Unit)
    fun put(route: Put.() -> Unit)
    fun delete(route: Delete.() -> Unit)
    fun registerRouters(route: RouteDefinition.() -> Unit)
    fun build(config: Map<String, Any>): Boolean
    fun deployOn(port: Int)
    fun stop()
}

open class Route : RouteDefinition {
    private var type: Type = Type.JERSEY
    private var path: String = "/"
    private var produces: List<String> = emptyList()
    private lateinit var controllerName: String
    private var consumes: List<String> = emptyList()
    private var name: String = ""
    private var middlewares: List<String> = emptyList()
    private var queryParams: Map<String, KClass<*>> = emptyMap()
    private var matrixParams: Map<String, KClass<*>> = emptyMap()
    private var headerParams: Map<String, KClass<*>> = emptyMap()
    private var cookieParams: Map<String, KClass<*>> = emptyMap()
    private var formParams: Map<String, KClass<*>> = emptyMap()
    private var response: KClass<*>? = null
    private var script: String = ""
    private var postMethods: MutableList<Any> = mutableListOf()
    private var getMethods: MutableList<RouteDefinition.() -> Unit> = mutableListOf()
    private var updateMethods: MutableList<RouteDefinition.() -> Unit> = mutableListOf()
    private var deleteMethods: MutableList<RouteDefinition.() -> Unit> = mutableListOf()
    private var textFileHandler: TextFileHandlerImpl = TextFileHandlerImpl()

    override fun type(type: () -> Type) {
        this.type = type()
    }

    override fun path(path: () -> String) {
        this.path = path()
    }

    override fun name(name: () -> String) {
        this.name = name()
    }

    override fun middlewares(middlewares: () -> List<String>) {
        this.middlewares = middlewares()
    }

    override fun queryParams(queryParams: () -> Map<String, KClass<*>>) {
        this.queryParams = queryParams()
    }

    override fun matrixParams(matrixParams: () -> Map<String, KClass<*>>) {
        this.matrixParams = matrixParams()
    }

    override fun headerParams(headerParams: () -> Map<String, KClass<*>>) {
        this.headerParams = headerParams()
    }

    override fun cookieParams(cookieParams: () -> Map<String, KClass<*>>) {
        this.cookieParams = cookieParams()
    }

    override fun formParams(formParams: () -> Map<String, KClass<*>>) {
        this.formParams = formParams()
    }

    override fun response(response: () -> KClass<*>) {
        this.response = response()
    }

    override fun script(script: () -> String) {
        this.script = script()
    }

    override fun controllerName(controllerName: () -> String) {
        this.controllerName = controllerName()
    }

    override fun produces(produces: () -> List<String>) {
        this.produces = produces()
    }

    override fun consumes(consumes: () -> List<String>) {
        this.consumes = consumes()
    }

    override fun post(route: Post.() -> Unit) {
        val post = Post().apply(route)

        this.postMethods.add(post)
    }

    override fun get(route: Get.() -> Unit) {
        Get().apply(route)
    }

    override fun put(route: Put.() -> Unit) {
        Put().apply(route)
    }

    override fun delete(route: Delete.() -> Unit) {
        Delete().apply(route)
    }

    override fun registerRouters(route: RouteDefinition.() -> Unit) {
        route()
    }

    override fun build(config: Map<String, Any>): Boolean {
        val modelProject = File(env("MODEL_BACK_PROJECT_URL"))

        File("${modelProject.name}.zip").delete()

        this.setRootProjectNameFor(modelProject, config["name"] as String)

        this.setVersionFor(modelProject, config["moduleVersion"] as String)

        this.buildController(modelProject)

        return true
    }

    private fun buildController(modelProject: File) {
        val routerScope = this

        ModelController.build("${modelProject.path}/src/main/kotlin/controllers/ModelController.kt") {
            this.controllerPath = routerScope.path

            this.controllerProduces = routerScope.produces

            this.controllerName = routerScope.controllerName

            routerScope.postMethods.forEach {
                val route = it as Route

                val method = Method(
                    route.name,
                    Action.POST,
                    route.path,
                    route.consumes,
                    route.produces,
                    route.queryParams,
                    route.matrixParams,
                    route.headerParams,
                    route.cookieParams,
                    route.formParams,
                    route.response,
                    route.script,
                    (route as Post).body
                )

                this.methods.add(method)
            }
        }.build()
    }

    override fun deployOn(port: Int) {
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    private fun setRootProjectNameFor(modelProject: File, name: String) {
        this.textFileHandler.using("${modelProject.path}/settings.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("rootProject.name = 'model-project'"),
            "rootProject.name = '${name}'",
            overrideOriginal = true
        )
    }

    private fun setVersionFor(modelProject: File, version: String) {
        this.textFileHandler.using("${modelProject.path}/build.gradle")
        this.textFileHandler.replaceLine(
            this.textFileHandler.getNumberLineFor("version = '{VERSION}'"),
            "version = '${version}'",
            overrideOriginal = true
        )
    }
}

class Post : Route() {
    var body: KClass<*>? = null

    fun body(body: () -> KClass<*>) {
        this.body = body()
    }
}

class Get : Route()

class Put : Route() {
    var body: KClass<*>? = null

    fun body(body: () -> KClass<*>) {
        this.body = body()
    }
}

class Delete : Route()
