package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.interfaces.Container
import com.koupper.container.KoupperContainer
import com.koupper.container.app
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBPSQLConnector
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.dispatch.Sender
import com.koupper.providers.dispatch.SenderHtmlEmail
import com.koupper.providers.dispatch.SenderServiceProvider
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import com.koupper.providers.parsing.TextParserHtmlEmailTemplate
import com.koupper.providers.parsing.TextParserServiceProvider
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

    @Test
    fun `should execute script sentence`() {
        val octopus = Octopus(this.container)
        octopus.run("val valueNumber = (0..10).random()") { result: Int ->
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

        octopus.runScriptFileFromUrl("https://yourdomain.com/script.kt") { result: Container ->
            // validate here
        }
    }

    @Ignore
    @Test
    fun `should read script from file`() {
        val containerImplementation = app

        val octopus = Octopus(containerImplementation)

        octopus.registerBuildInServicesProvidersInContainer()

        octopus.runScriptFileFromUrl("script.kt") { result: Container ->
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
                            TextParserServiceProvider::class,
                            SenderServiceProvider::class,
                            DBServiceProvider::class
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
                    assertTrue {
                        (value as () -> Any).invoke() is TextParserEnvPropertiesTemplate || (value as () -> Any).invoke() is TextParserHtmlEmailTemplate
                    }
                }
            } else {
                assertTrue {
                    abstractClass.java.name == DBConnector::class.qualifiedName || abstractClass.java.name == Sender::class.qualifiedName || abstractClass.java.name == TextParser::class.qualifiedName
                    (value as () -> Any).invoke() is DBPSQLConnector || (value as () -> Any).invoke() is SenderHtmlEmail
                }
            }
        }
    }
}
