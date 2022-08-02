package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.interfaces.Container
import com.koupper.container.KoupperContainer
import com.koupper.container.app
import com.koupper.providers.crypto.CryptoServiceProvider
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.files.FileServiceProvider
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.jwt.JWTServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.mailing.SenderServiceProvider
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
}
