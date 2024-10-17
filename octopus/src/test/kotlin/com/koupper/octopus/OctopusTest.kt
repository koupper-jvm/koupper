package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.interfaces.Container
import com.koupper.container.KoupperContainer
import com.koupper.container.app
import com.koupper.octopus.modules.http.Route
import com.koupper.providers.crypto.CryptoServiceProvider
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.files.*
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.jwt.JWTServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
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

    private var envs: Map<String, String> = mapOf(
        "MODEL_BACK_PROJECT_URL" to "/Users/jacobacosta/Code/model-project",
        "OPTIMIZED_PROCESS_MANAGER_URL" to "https://koupper.s3.us-east-2.amazonaws.com/cli/optimized/octopus-4.0.0.jar",
        "OCTOPUS_VERSION" to "4.0.0",
        "KOUPPER_PATH" to "/Users/jacobacosta/Code/koupper/octopus/src/test/resources/init.kts"
    )

    @Ignore
    @Test
    fun `should build a route`() {
        every {
            container.createInstanceOf(TextFileHandler::class)
        } answers {
            TextFileHandlerImpl()
        }

        every {
            container.createInstanceOf(FileHandler::class)
        } answers {
            FileHandlerImpl()
        }

        withEnvironment(envs) {
            Route(this.container).registerRouter {
                path { "post" }
                controllerName { "PostController" }

                get {
                    path { "/helloWorld/{example}" }
                    identifier { "getPost" }
                    middlewares { listOf("jwt-auth") }
                    queryParams { mapOf("name" to String::class) }
                    response { Int::class }
                    script { "create-post" }
                    produces { listOf("application/json") }
                    consumes { listOf("application/json") }
                }

            }.deployOn {
                port = 8080
                rootUrl = "example"
            }.setup {
                projectName = "hello"
                version = "2.0.1"
                packageName = "io.example"
            }
        }
    }
}
