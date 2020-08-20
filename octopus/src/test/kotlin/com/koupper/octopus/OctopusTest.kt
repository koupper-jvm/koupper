package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.interfaces.Container
import com.koupper.container.KupContainer
import com.koupper.container.app
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBPSQLConnector
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.despatch.Sender
import com.koupper.providers.despatch.SenderHtmlEmail
import com.koupper.providers.despatch.SenderServiceProvider
import com.koupper.providers.parsing.TextParser
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import com.koupper.providers.parsing.TextParserHtmlEmailTemplate
import com.koupper.providers.parsing.TextParserServiceProvider
import io.mockk.every
import io.mockk.mockkClass
import zigocapital.providers.ZigoServiceProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctopusTest : AnnotationSpec() {
    private lateinit var container: Container

    @BeforeEach
    fun initialize() {
        this.container = mockkClass(KupContainer::class)
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
            container.create()
        } returns container

        octopus.run("val container: (Container) -> Container = {\n" +
                "\tit.create()\n" +
                "}") { result: Container ->
            assertEquals(this.container, result)
        }
    }

    @Test
    fun `should read script from file`() {
        val containerImplementation = app

        val octopus = Octopus(containerImplementation)

        octopus.registerBuildInBindingsInContainer()

        octopus.registerExternalServiceProviders(listOf(
                ZigoServiceProvider()
        ))

        octopus.runScriptFile("/Users/jacobacosta/Code/koupper/octopus/src/main/resources/example.kts") { result: Container ->
            assertEquals(containerImplementation, result)
        }
    }

    @Test
    fun `should return the available service providers`() {
        val octopus = Octopus(KupContainer())

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
    fun `should bind the available service providers to container`() {
        val containerImplementation = KupContainer()

        val octopus = Octopus(containerImplementation)

        octopus.registerBuildInBindingsInContainer().forEach { (abstractClass, value) ->
            if (value is Map<*, *>) {
                value.forEach { (key, value) ->
                    assertTrue {
                        (value as () -> Any).invoke() is TextParserEnvPropertiesTemplate || (value as () -> Any).invoke() is TextParserHtmlEmailTemplate
                    }
                }
            } else {
                assertTrue {
                    abstractClass.java.name == DBConnector::class.qualifiedName || abstractClass.java.name ==  Sender::class.qualifiedName || abstractClass.java.name == TextParser::class.qualifiedName
                    (value as () -> Any).invoke() is DBPSQLConnector || (value as () -> Any).invoke() is SenderHtmlEmail
                }
            }
        }

        app.getBindings().clear()
    }
}
