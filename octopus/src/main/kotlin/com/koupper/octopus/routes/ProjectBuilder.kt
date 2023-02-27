package com.koupper.octopus.routes

import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.ModuleMaker

enum class ProjectType {
    MVN,
    GRADLE,
}

class ProjectBuilder private constructor(
    private var location: String = Property.UNDEFINED.name,
    private val container: Container,
    var buildingTool: ProjectType = ProjectType.GRADLE,
    var type: Type = Type.JERSEY,
    var name: String = "undefined",
    var version: String = "undefined",
    private var routeDefinition: RouteDefinition? = null
) {

    private constructor(builder: Builder) : this(
        builder.location,
        builder.container,
        builder.buildingTool,
        builder.type,
        builder.name,
        builder.version,
        builder.routeDefinition,
    )

    companion object {
        inline fun build(location: String, container: Container, block: Builder.() -> Unit) =
            Builder(location, container).apply(block).build()
    }

    fun build() {
        ModuleMaker(this.container).register(
            this.name,
            mapOf(
                "moduleType" to "CONTROLLER",
                "moduleVersion" to this.version,
                "methods" to this.getRegisteredScripts(),
                "projectLocation" to this.location
            )
        ).run()

        if (this.routeDefinition != null) {
            if (this.type == Type.JERSEY) {
                this.setupJersey()
            }
        }
    }

    private fun getRegisteredScripts(): List<RouteDefinition> {
        val result = mutableListOf<RouteDefinition>()
        result.addAll(
            this.routeDefinition!!.postMethods()
        )
        result.addAll(
            this.routeDefinition!!.getMethods()
        )
        result.addAll(
            this.routeDefinition!!.putMethods()
        )
        result.addAll(
            this.routeDefinition!!.deleteMethods()
        )

        return result
    }

    private fun setupJersey() {
        JerseyControllerBuilder.build(this.name) {
            this.path = routeDefinition!!.path()

            this.controllerConsumes = routeDefinition!!.consumes()

            this.controllerProduces = routeDefinition!!.produces()

            this.controllerName = routeDefinition!!.controllerName()

            val pm = postMethods()

            if (pm.isNotEmpty()) {
                this.methods.addAll(pm)
            }

            val gm = getMethods()

            if (gm.isNotEmpty()) {
                this.methods.addAll(gm)
            }

            val pum = putMethods()

            if (pum.isNotEmpty()) {
                this.methods.addAll(pum)
            }

            val dm = deleteMethods()

            if (dm.isNotEmpty()) {
                this.methods.addAll(dm)
            }
        }.build()
    }

    private fun postMethods(): List<Method> {
        val methods = mutableListOf<Method>()

        routeDefinition!!.postMethods().forEach {
            val route = it as Route

            val method = Method(
                route.identifier(),
                Action.POST,
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
                (route as Post).body
            )

            methods.add(method)
        }

        return methods
    }

    private fun getMethods(): List<Method> {
        val methods = mutableListOf<Method>()

        routeDefinition!!.getMethods().forEach {
            val route = it as Route

            val method = Method(
                route.identifier(),
                Action.GET,
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
                null
            )

            methods.add(method)
        }

        return methods
    }

    private fun putMethods(): List<Method> {
        val methods = mutableListOf<Method>()

        routeDefinition!!.putMethods().forEach {
            val route = it as Route

            val method = Method(
                route.identifier(),
                Action.PUT,
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
                (route as Put).body
            )

            methods.add(method)
        }

        return methods
    }

    private fun deleteMethods(): List<Method> {
        val methods = mutableListOf<Method>()

        routeDefinition!!.deleteMethods().forEach {
            val route = it as Route

            val method = Method(
                route.identifier(),
                Action.DELETE,
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
                null
            )

            methods.add(method)
        }

        return methods
    }

    class Builder(var location: String, var container: Container) {
        var buildingTool = ProjectType.GRADLE
        var type = Type.JERSEY
        var name = Property.UNDEFINED.name
        var version = "1.0.0"
        var routeDefinition: RouteDefinition? = null

        fun build() = ProjectBuilder(this)
    }
}
