/**
 * SSH Remote Tree Demo
 *
 * Purpose:
 * - Print a remote directory tree (or fallback listing) from the server root path using SSH.
 *
 * Typical run:
 * - koupper run examples/ssh-tree-root.kts --json-file examples/ssh-tree-root.input.json
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.ssh.SSHClient

data class Input(
    val rootPath: String = ".",
    val maxDepth: Int = 3,
    val includeHidden: Boolean = true
)

@Export
val sshTreeRoot: (Input) -> String = { input ->
    val ssh = app.getInstance(SSHClient::class)

    val depth = input.maxDepth.coerceIn(1, 10)
    val root = input.rootPath.trim().ifBlank { "." }
    val hiddenFlag = if (input.includeHidden) "-a" else ""

    val command = """
        ROOT='${root.replace("'", "'\\''")}'
        if command -v tree >/dev/null 2>&1; then
          tree $hiddenFlag -L $depth "${'$'}ROOT"
        else
          find "${'$'}ROOT" -maxdepth $depth -print | sed "s#^${'$'}ROOT#.#"
        fi
    """.trimIndent()

    val result = ssh.exec(command)
    val output = result.stdout.ifBlank { "(no output)" }

    println("Remote root: $root")
    println("Depth: $depth")
    println(output)

    output
}
