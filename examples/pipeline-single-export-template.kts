import com.koupper.octopus.ScriptExecutor
import com.koupper.octopus.annotations.Export
import com.koupper.shared.octopus.dependsOn
import java.util.concurrent.CompletableFuture

data class Input(
    val name: String = "Koupper"
)

private lateinit var currentInput: Input
private var greeting: String = ""
private var transformed: String = ""

val buildGreeting: () -> Unit = {
    greeting = "Hello, ${currentInput.name}!"
}

val transformGreeting: () -> Unit = {
    transformed = greeting.uppercase()
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    currentInput = input

    val done = CompletableFuture<Unit>()
    ScriptExecutor.runPipeline(
        listOf(
            ::buildGreeting,
            ::transformGreeting.dependsOn(::buildGreeting)
        ),
        async = false
    ) {
        done.complete(Unit)
    }
    done.get()

    mapOf(
        "ok" to true,
        "greeting" to greeting,
        "transformed" to transformed
    )
}
