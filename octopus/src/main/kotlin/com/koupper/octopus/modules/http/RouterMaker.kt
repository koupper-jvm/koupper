package com.koupper.octopus.modules.http

import com.koupper.container.interfaces.Container
import com.koupper.octopus.modifiers.SetupGrizzlyConfigurator
import com.koupper.os.env
import java.io.File
import kotlin.reflect.KClass

sealed interface RouteDefinition {
    fun path(path: () -> String)
    fun path(): String
    fun identifier(identifier: () -> String)
    fun identifier(): String
    fun middlewares(middlewares: () -> List<String>)
    fun middlewares(): List<String>
    fun queryParams(queryParams: () -> Map<String, KClass<*>>)
    fun queryParams(): Map<String, KClass<*>>
    fun matrixParams(matrixParams: () -> Map<String, KClass<*>>)
    fun matrixParams(): Map<String, KClass<*>>
    fun headerParams(headerParams: () -> Map<String, KClass<*>>)
    fun headerParams(): Map<String, KClass<*>>
    fun cookieParams(cookieParams: () -> Map<String, KClass<*>>)
    fun cookieParams(): Map<String, KClass<*>>
    fun formParams(formParams: () -> Map<String, KClass<*>>)
    fun formParams(): Map<String, KClass<*>>
    fun response(response: () -> KClass<*>)
    fun response(): KClass<*>?
    fun script(script: () -> String)
    fun handler(handler: () -> String)
    fun script(): String
    fun controllerName(controllerName: () -> String)
    fun controllerName(): String
    fun produces(produces: () -> List<String>)
    fun produces(): List<String>
    fun consumes(consumes: () -> List<String>)
    fun consumes(): List<String>
    fun post(route: Post.() -> Unit)
    fun postMethods(): MutableList<RouteDefinition>
    fun get(route: Get.() -> Unit)
    fun getMethods(): MutableList<RouteDefinition>
    fun put(route: Put.() -> Unit)
    fun putMethods(): MutableList<RouteDefinition>
    fun delete(route: Delete.() -> Unit)
    fun deleteMethods(): MutableList<RouteDefinition>
    fun registerRouter(route: RouteDefinition.() -> Unit): RouteDefinition
    fun deployOn(config: SetupGrizzlyConfigurator.Builder.() -> Unit): RouteDefinition
    fun stop()
}

private var modelProject: File =
    File(env("MODEL_BACK_PROJECT_URL"))//FileHandlerImpl().unzipFile(env("MODEL_BACK_PROJECT_URL"))

open class Route(private val container: Container) : RouteDefinition {
    private var path: String = "/"
    private var produces: List<String> = emptyList()
    private lateinit var controllerName: String
    private var consumes: List<String> = emptyList()
    private var identifier: String = ""
    private var middlewares: List<String> = emptyList()
    private var queryParams: Map<String, KClass<*>> = emptyMap()
    private var matrixParams: Map<String, KClass<*>> = emptyMap()
    private var headerParams: Map<String, KClass<*>> = emptyMap()
    private var cookieParams: Map<String, KClass<*>> = emptyMap()
    private var formParams: Map<String, KClass<*>> = emptyMap()
    private var response: KClass<*>? = null
    private var script: String = ""
    private var handler: String = ""
    private var postMethods: MutableList<RouteDefinition> = mutableListOf()
    private var getMethods: MutableList<RouteDefinition> = mutableListOf()
    private var putMethods: MutableList<RouteDefinition> = mutableListOf()
    private var deleteMethods: MutableList<RouteDefinition> = mutableListOf()
    private var location: String = modelProject.path
    private var port: String = "8000"
    private var rootURL: String = ""

    init {
        File(location).delete()
    }

    override fun path(path: () -> String) {
        this.path = path()
    }

    override fun path() = this.path

    override fun identifier(identifier: () -> String) {
        this.identifier = identifier()
    }

    override fun identifier() = this.identifier

    override fun middlewares(middlewares: () -> List<String>) {
        this.middlewares = middlewares()
    }

    override fun middlewares() = this.middlewares

    override fun queryParams(queryParams: () -> Map<String, KClass<*>>) {
        this.queryParams = queryParams()
    }

    override fun queryParams() = this.queryParams

    override fun matrixParams(matrixParams: () -> Map<String, KClass<*>>) {
        this.matrixParams = matrixParams()
    }

    override fun matrixParams() = this.matrixParams

    override fun headerParams(headerParams: () -> Map<String, KClass<*>>) {
        this.headerParams = headerParams()
    }

    override fun headerParams() = this.headerParams

    override fun cookieParams(cookieParams: () -> Map<String, KClass<*>>) {
        this.cookieParams = cookieParams()
    }

    override fun cookieParams() = this.cookieParams

    override fun formParams(formParams: () -> Map<String, KClass<*>>) {
        this.formParams = formParams()
    }

    override fun formParams() = this.formParams

    override fun response(response: () -> KClass<*>) {
        this.response = response()
    }

    override fun response() = this.response

    override fun script(script: () -> String) {
        this.script = script()
    }

    override fun script() = this.script

    override fun handler(handler: () -> String) {
        this.handler = handler()
    }

    override fun controllerName(controllerName: () -> String) {
        this.controllerName = controllerName()
    }

    override fun controllerName() = this.controllerName

    override fun produces(produces: () -> List<String>) {
        this.produces = produces()
    }

    override fun produces() = this.produces

    override fun consumes(consumes: () -> List<String>) {
        this.consumes = consumes()
    }

    override fun consumes() = this.consumes

    override fun post(route: Post.() -> Unit) {
        val post = Post(container).apply(route)

        this.postMethods.add(post)
    }

    override fun postMethods() = this.postMethods

    override fun get(route: Get.() -> Unit) {
        val get = Get(container).apply(route)

        this.getMethods.add(get)
    }

    override fun getMethods() = this.getMethods

    override fun put(route: Put.() -> Unit) {
        val put = Put(container).apply(route)

        this.putMethods.add(put)
    }

    override fun putMethods() = this.putMethods

    override fun delete(route: Delete.() -> Unit) {
        val delete = Delete(container).apply(route)

        this.deleteMethods.add(delete)
    }

    override fun deleteMethods() = this.deleteMethods

    override fun registerRouter(route: RouteDefinition.() -> Unit): RouteDefinition {
        route()

        return this
    }

    override fun deployOn(config: SetupGrizzlyConfigurator.Builder.() -> Unit): RouteDefinition {
        SetupGrizzlyConfigurator.configure(config)

        return this
    }

    override fun stop() {
        TODO("Not yet implemented")
    }
}

class Post(container: Container) : Route(container) {
    var body: KClass<*>? = null

    fun body(body: () -> KClass<*>) {
        this.body = body()
    }
}

class Get(container: Container) : Route(container)

class Put(container: Container) : Route(container) {
    var body: KClass<*>? = null

    fun body(body: () -> KClass<*>) {
        this.body = body()
    }
}

class Delete(container: Container) : Route(container)
