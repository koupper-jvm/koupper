package com.koupper.providers.controller

import com.koupper.providers.controllers.Route
import com.koupper.providers.controllers.Type
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.extensions.system.withEnvironment

data class Post(val prop1: Int, val prop2: String)

data class Post2(val prop1: Int, val prop2: String)

class RouterMakerTest : AnnotationSpec() {
    private var envs: Map<String, String> = mapOf(
        "MODEL_BACK_PROJECT_URL" to "/Users/jacobacosta/Code/model-project",
    )

    @Test
    fun `should build a route`() {
        withEnvironment(envs) {
            val routerMaker = Route()

            routerMaker.registerRouters {
                path { "post" }
                controllerName { "Post" }
                type { Type.JERSEY }
                produces { listOf("application/json") }
                post {
                    path { "/helloWorld/{example}" }
                    name = "createPost"
                    middlewares = listOf("jwt-auth")
                    queryParams = mapOf("name" to String::class)
                    matrixParams = mapOf(
                        "lat" to String::class,
                        "long" to String::class,
                        "scale" to String::class,
                    )
                    headerParams = mapOf("name" to String::class)
                    cookieParams = mapOf("sessionId" to String::class)
                    formParams = mapOf("user" to String::class)
                    body = Post::class
                    response = Int::class
                    script = "create-post"
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }

                post {
                    path { "/helloWorld/{example}" }
                    name = "createPost"
                    middlewares = listOf("jwt-auth")
                    queryParams = mapOf("name" to String::class)
                    matrixParams = mapOf(
                        "lat" to String::class,
                        "long" to String::class,
                        "scale" to String::class,
                    )
                    headerParams = mapOf("name" to String::class)
                    cookieParams = mapOf("sessionId" to String::class)
                    formParams = mapOf("user" to String::class)
                    body = Post::class
                    response = Int::class
                    script = "create-post"
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }
            }

            routerMaker.build(
                mapOf("name" to "hello", "moduleVersion" to "2.0.1")
            )
        }
    }
}