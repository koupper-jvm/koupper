package com.koupper.providers

import com.koupper.container.app
import com.koupper.providers.controllers.ControllerServiceProvider
import com.koupper.providers.controllers.Route
import com.koupper.providers.controllers.RouteDefinition
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class ControllerServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind the router maker implementation`() {
        ControllerServiceProvider().up()

        assertTrue {
            app.createInstanceOf(RouteDefinition::class) is Route
        }
    }
}