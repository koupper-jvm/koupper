package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.interfaces.Container
import com.koupper.container.KoupperContainer
import com.koupper.container.app
import com.koupper.octopus.routes.ProjectType
import com.koupper.octopus.routes.Route
import com.koupper.octopus.routes.Type
import com.koupper.providers.crypto.Crypt0
import com.koupper.providers.crypto.CryptoServiceProvider
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBPSQLConnector
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.FileServiceProvider
import com.koupper.providers.files.JSONFileHandler
import com.koupper.providers.files.TextFileHandler
import com.koupper.providers.http.HtppClient
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.jwt.JWT
import com.koupper.providers.jwt.JWTServiceProvider
import com.koupper.providers.logger.Logger
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.mailing.Sender
import com.koupper.providers.mailing.SenderHtmlEmail
import com.koupper.providers.mailing.SenderServiceProvider
import io.kotest.extensions.system.withEnvironment
import io.mockk.every
import io.mockk.mockkClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctopusTest : AnnotationSpec() {
    private lateinit var container: Container
    private lateinit var octopus: Octopus

    @BeforeClass
    fun globalSetup() {
        val containerImplementation = app

        this.octopus = Octopus(containerImplementation)

        octopus.registerBuildInServicesProvidersInContainer()
    }

    @BeforeEach
    fun initialize() {
        this.container = mockkClass(KoupperContainer::class)
    }

    @Ignore
    @Test
    fun `should execute script sentence`() {
        val octopus = Octopus(this.container)
        octopus.run("val valueNumber = (0..10).random()", emptyMap()) { result: Int ->
            assertTrue {
                result is Int
            }
        }
    }

    @Test
    fun `should inject container to callback script variable`() {
        val octopus = Octopus(this.container)

        every {
            container.createInstanceOf(Any::class)
        } returns container

        octopus.run(
            "import com.koupper.container.interfaces.Container\n val container: (Container) -> Container = {\n" +
                    "\tit\n" +
                    "}"
        ) { result: Container ->
            assertEquals(this.container, result)
        }
    }

    @Ignore
    @Test
    fun `should read script from url`() {
        val containerImplementation = app

        val octopus = Octopus(containerImplementation)

        octopus.registerBuildInServicesProvidersInContainer()

        octopus.runFromUrl("https://yourdomain.com/script.kt") { result: Container ->
            // validate here
        }

    }

    @Test
    fun `should read script from file`() {
        this.octopus.runFromScriptFile("resource://example.kts") { result: Container ->
            assertEquals(app, result)
        }
    }

    @Test
    fun `should return the available service providers`() {
        val availableServiceProviders = this.octopus.availableServiceProviders()

        assertTrue {
            availableServiceProviders.containsAll(
                listOf(
                    DBServiceProvider::class,
                    SenderServiceProvider::class,
                    LoggerServiceProvider::class,
                    HttpServiceProvider::class,
                    FileServiceProvider::class,
                    JWTServiceProvider::class,
                    CryptoServiceProvider::class
                )
            )
        }
    }

    @Test
    fun `should bind the available service providers in container`() {
        val containerImplementation = KoupperContainer()

        val octopus = Octopus(containerImplementation)

        octopus.registerBuildInServicesProvidersInContainer().forEach { (abstractClass, value) ->
            if (value is Map<*, *>) {
                value.forEach { (key, value) ->
                }
            } else {
                assertTrue {
                    abstractClass.java.name == Crypt0::class.qualifiedName ||
                            abstractClass.java.name == DBConnector::class.qualifiedName ||
                            abstractClass.java.name == FileHandler::class.qualifiedName ||
                            abstractClass.java.name == JSONFileHandler::class.qualifiedName ||
                            abstractClass.java.name == TextFileHandler::class.qualifiedName ||
                            abstractClass.java.name == HtppClient::class.qualifiedName ||
                            abstractClass.java.name == JWT::class.qualifiedName ||
                            abstractClass.java.name == Logger::class.qualifiedName ||
                            abstractClass.java.name == Sender::class.qualifiedName ||
                    (value as () -> Any).invoke() is DBPSQLConnector || (value as () -> Any).invoke() is SenderHtmlEmail
                }
            }
        }
    }

    private var envs: Map<String, String> = mapOf(
        "MODEL_BACK_PROJECT_URL" to "/Users/jacobacosta/Code/model-project",
    )

    data class Body(val prop1: Int, val prop2: String)

    data class Post2(val prop1: Int, val prop2: String)

    @Ignore
    @Test
    fun `should build a route`() {
        withEnvironment(envs) {
            Route(this.container).registerRouters {
                path { "post" }
                controllerName { "Post" }
                produces { listOf("application/json") }
                post {
                    path { "/helloWorld/{example}" }
                    identifier { "createPost" }
                    middlewares { listOf("jwt-auth") }
                    queryParams { mapOf("name" to String::class) }
                    matrixParams {
                        mapOf(
                            "lat" to String::class,
                            "long" to String::class,
                            "scale" to String::class,
                        )
                    }
                    headerParams { mapOf("name" to String::class) }
                    cookieParams { mapOf("sessionId" to String::class) }
                    formParams { mapOf("user" to String::class) }
                    body { Body::class }
                    response { Int::class }
                    script { "create-post" }
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }

                put {
                    path { "/helloWorld/{example}" }
                    identifier { "updatePost" }
                    middlewares { listOf("jwt-auth") }
                    queryParams { mapOf("name" to String::class) }
                    cookieParams { mapOf("sessionId" to String::class) }
                    body { Body::class }
                    response { Int::class }
                    script { "create-post" }
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }

                get {
                    path { "/helloWorld/{example}" }
                    identifier { "getPost" }
                    middlewares { listOf("jwt-auth") }
                    queryParams { mapOf("name" to String::class) }
                    cookieParams { mapOf("sessionId" to String::class) }
                    response { Int::class }
                    script { "create-post" }
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }

                delete {
                    path { "/helloWorld/{example}" }
                    identifier { "deletePost" }
                    middlewares { listOf("jwt-auth") }
                    queryParams { mapOf("name" to String::class) }
                    cookieParams { mapOf("sessionId" to String::class) }
                    response { Int::class }
                    script { "create-post" }
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }
            }.deployOn {
                port = 8080
                rootUrl = "http://localhost/"
            }.setup {
                name = "hello"
                type = Type.JERSEY
                buildingTool = ProjectType.GRADLE
                version = "2.0.1"
            }.build()
        }
    }
}
