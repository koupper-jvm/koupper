package io.kup.octopus

import io.kotest.core.spec.style.AnnotationSpec
import io.kup.container.Container
import io.kup.container.KupContainer
import io.mockk.every
import io.mockk.mockkClass
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
}
