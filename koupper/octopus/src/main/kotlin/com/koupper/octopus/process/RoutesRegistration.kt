package com.koupper.octopus.process

import com.koupper.container.app
import com.koupper.octopus.modifiers.Action
import com.koupper.octopus.modifiers.JerseyControllerBuilder
import com.koupper.octopus.modifiers.RequestHandlerControllerBuilder
import com.koupper.octopus.modules.http.Post
import com.koupper.octopus.modules.http.Put
import com.koupper.octopus.modules.http.Route
import com.koupper.octopus.modules.http.RouteDefinition

class RoutesRegistration(private val context: String) : Route(app) {
    private var modelProject: String = ""
    private lateinit var controllerLocation: String
    private var packageName: String = "http.controllers"

    @Override
    override fun registerRouter(route: RouteDefinition.() -> Unit): RouteDefinition {
        val routeDefinition = super.registerRouter(route)

        val self = this

        RequestHandlerControllerBuilder.build {
            this.modelProject = self.modelProject

            this.context = self.context

            if (self.controllerLocation.isNotEmpty()) this.controllerLocation = self.controllerLocation

            this.path = self.path()

            this.controllerConsumes = self.consumes()

            this.controllerProduces = self.produces()

            this.controllerName = self.controllerName()

            this.packageName = self.packageName

            val pm = JerseyControllerBuilder.generateMethods(Action.POST, self.postMethods()) {
                (it as Post).body
            }

            if (pm.isNotEmpty()) {
                this.methods.addAll(pm)
            }

            val gm = JerseyControllerBuilder.generateMethods(Action.GET, self.getMethods()) {
                null
            }

            if (gm.isNotEmpty()) {
                this.methods.addAll(gm)
            }

            val pum = JerseyControllerBuilder.generateMethods(Action.PUT, self.putMethods()) {
                (it as Put).body
            }

            if (pum.isNotEmpty()) {
                this.methods.addAll(pum)
            }

            val dm = JerseyControllerBuilder.generateMethods(Action.DELETE, self.deleteMethods()) {
                null
            }

            if (dm.isNotEmpty()) {
                this.methods.addAll(dm)
            }
        }

        return routeDefinition
    }

    fun withModelProject(value: String): RoutesRegistration {
        this.modelProject = value
        return this
    }

    fun withControllerLocation(value: String): RoutesRegistration {
        this.controllerLocation = value
        return this
    }

    fun withPackageName(value: String): RoutesRegistration {
        this.packageName = value
        return this
    }
}