package com.koupper.octopus.modules.http

import com.koupper.container.app
import com.koupper.octopus.modifiers.Action
import com.koupper.octopus.modifiers.JerseyControllerBuilder
import com.koupper.octopus.modifiers.RequestHandlerControllerBuilder
import com.koupper.octopus.modules.Module
import com.koupper.octopus.modules.aws.API
import com.koupper.octopus.modules.aws.loadAPIDefinitionFromConfiguration

class ControllersBuilder private constructor(
    private val context: String,
    private val packageName: String,
    private val projectName: String
): Module() {
    private lateinit var routeDefinition: RouteDefinition
    private var apiDefinition: List<API> = loadAPIDefinitionFromConfiguration(context, projectName)
    private var contextPath = "/"

    constructor(builder: Builder) : this(
        builder.context,
        builder.packageName,
        builder.projectName,
    ) {
        this.registeredScripts.putAll(builder.registeredScripts)
    }

    companion object {
        inline fun build(block: Builder.() -> Unit) = Builder().apply(block).build().build()
    }

    override fun build() {
        this.buildRequestHandlerController()
    }

    private fun buildRequestHandlerController() {
        val routerMaker = Route(app)

        this.routeDefinition = routerMaker.registerRouter {
            apiDefinition.forEach { api ->
                path { contextPath }

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
            this.modelProject = self.projectName

            this.context = self.context

            this.controllerLocation =
                "${self.projectName}/src/main/kotlin/io/mp/controllers/RequestHandlerController.kt"

            this.path = routeDefinition.path()

            this.controllerConsumes = routeDefinition.consumes()

            this.controllerProduces = routeDefinition.produces()

            this.controllerName = routeDefinition.controllerName()

            this.packageName = self.packageName

            val pm = JerseyControllerBuilder.generateMethods(Action.POST, self.routeDefinition.postMethods()) {
                (it as Post).body
            }

            if (pm.isNotEmpty()) {
                this.methods.addAll(pm)
            }

            val gm = JerseyControllerBuilder.generateMethods(Action.GET, self.routeDefinition.getMethods()) {
                null
            }

            if (gm.isNotEmpty()) {
                this.methods.addAll(gm)
            }

            val pum = JerseyControllerBuilder.generateMethods(Action.PUT, self.routeDefinition.putMethods()) {
                (it as Put).body
            }

            if (pum.isNotEmpty()) {
                this.methods.addAll(pum)
            }

            val dm = JerseyControllerBuilder.generateMethods(Action.DELETE, self.routeDefinition.deleteMethods()) {
                null
            }

            if (dm.isNotEmpty()) {
                this.methods.addAll(dm)
            }
        }
    }

    class Builder {
        var context: String = ""
        var packageName: String = ""
        var projectName: String = ""
        var registeredScripts: Map<String, Pair<List<String>, String>> = emptyMap()

        fun build() = ControllersBuilder(this)
    }
}
