import com.koupper.octopus.annotations.Export
import com.koupper.providers.io.TerminalIO

data class Input(
    val name: String? = null,
    val askPrompt: Boolean = false
)

@Export
val terminalDemo: (Input?, TerminalIO) -> String = { input, terminal ->
    val initialName = input?.name?.takeIf { it.isNotBlank() }

    val pickedName = when {
        !initialName.isNullOrBlank() -> initialName
        input?.askPrompt == true -> {
            var value = ""
            terminal.prompt("What is your name?") { response ->
                value = response.trim()
            }
            value.ifBlank { "Developer" }
        }

        else -> "Developer"
    }

    terminal.print("Hello, $pickedName")
    "Done"
}
