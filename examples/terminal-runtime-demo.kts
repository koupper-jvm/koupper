/**
 * Terminal Runtime Demo
 *
 * Purpose:
 * - Show how a script can prompt users and print output through TerminalIO.
 *
 * Behavior:
 * - if input.name is provided, it prints greeting with that name.
 * - if askPrompt=true and name is empty, it asks interactively for the name.
 * - returns "Done" after printing greeting.
 *
 * Typical runs:
 * - koupper run examples/terminal-runtime-demo.kts '{"name":"Jacob"}'
 * - koupper run examples/terminal-runtime-demo.kts '{"askPrompt":true}'
 */
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
