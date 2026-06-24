import com.koupper.container.app
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

/**
 * Example CORE tier ServiceProvider.
 *
 * CORE providers must have:
 * - >80% test coverage
 * - Fully documented
 * - Exception-safe
 * - Schema-typed I/O
 *
 * CI gate: blocks merge if tests fail.
 */
class ExampleCoreProvider : ServiceProvider() {

    override fun tier() = ProviderTier.CORE

    override fun up() {
        app.bind(ExampleService::class, { ExampleServiceImpl() })
    }

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "greet" to """
            fun greet(name: String): String = "Hello, ${'$'}name!"
        """.trimIndent()
    )
}

interface ExampleService {
    fun greet(name: String): String
}

class ExampleServiceImpl : ExampleService {
    override fun greet(name: String) = "Hello, $name!"
}
