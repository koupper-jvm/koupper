package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.interfaces.Container
import com.koupper.container.KoupperContainer
import com.koupper.container.app
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
import io.mockk.every
import io.mockk.mockkClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctopusTest : AnnotationSpec() {
    private lateinit var container: Container

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

        octopus.run("import com.koupper.container.interfaces.Container\n val container: (Container) -> Container = {\n" +
                "\tit\n" +
                "}") { result: Container ->
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

    @Ignore
    @Test
    fun `should read script from file`() {
        val containerImplementation = app

        val octopus = Octopus(containerImplementation)

        octopus.registerBuildInServicesProvidersInContainer()

        octopus.runFromUrl("script.kt") { result: Container ->
            // validate here
        }
    }

    @Test
    fun `should return the available service providers`() {
        val octopus = Octopus(KoupperContainer())

        val availableServiceProviders = octopus.availableServiceProviders()

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
}
