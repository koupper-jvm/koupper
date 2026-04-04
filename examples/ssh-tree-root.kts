/**
 * SSH Remote Tree Demo
 *
 * Purpose:
 * - Print a remote directory tree (or fallback listing) from the server root path using SSH.
 *
 * Typical run:
 * - koupper run examples/ssh-tree-root.kts --json-file examples/ssh-tree-root.input.json
 */
import com.koupper.octopus.annotations.Export
import com.koupper.providers.io.TerminalIO
import com.koupper.providers.ssh.JschSSHClient
import com.koupper.providers.ssh.SSHConnectionConfig
import com.koupper.providers.ssh.SSHClient

data class Input(
    val sshHost: String? = null,
    val sshUser: String? = null,
    val sshPort: Int = 22,
    val sshIdentityFile: String? = null,
    val sshPassword: String? = null,
    val sshStrictHostKeyChecking: Boolean = false,
    val rootPath: String = ".",
    val maxDepth: Int = 3,
    val includeHidden: Boolean = true
)

@Export
val sshTreeRoot: (Input?, TerminalIO) -> String = { maybeInput, terminal ->
    val input = maybeInput ?: Input()

    fun pick(value: String?, envName: String, prompt: String): String {
        val env = System.getenv(envName)?.trim()
        if (!value.isNullOrBlank()) return value.trim()
        if (!env.isNullOrBlank()) return env
        var typed = ""
        terminal.prompt(prompt) { typed = it.trim() }
        return typed
    }

    val host = pick(input.sshHost, "SSH_HOST", "SSH host (ip/domain):")
    val user = pick(input.sshUser, "SSH_USER", "SSH username:")
    val password = input.sshPassword?.takeIf { it.isNotBlank() }
        ?: System.getenv("SSH_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: run {
            if (!input.sshIdentityFile.isNullOrBlank()) null
            else {
                var typed = ""
                terminal.prompt("SSH password (leave empty if key auth is configured):") { typed = it }
                typed.takeIf { it.isNotBlank() }
            }
        }

    val ssh: SSHClient = JschSSHClient(
        SSHConnectionConfig(
            host = host,
            username = user,
            port = input.sshPort,
            identityFile = input.sshIdentityFile,
            password = password,
            strictHostKeyChecking = input.sshStrictHostKeyChecking
        )
    )

    val depth = input.maxDepth.coerceIn(1, 10)
    val root = input.rootPath.trim().ifBlank { "." }

    fun formatBlock(title: String, body: String): String {
        val cleanBody = body.trimEnd()
        return buildString {
            appendLine("== $title ==")
            appendLine(cleanBody)
        }
    }

    val rendered = try {
        val tree = ssh.tree(
            rootPath = root,
            depth = depth,
            includeHidden = input.includeHidden
        )

        formatBlock(
            title = "SSH Tree",
            body = "host=$host user=$user root=${tree.rootPath} depth=${tree.depth} source=${tree.source}\n${tree.rendered}"
        )
    } catch (t: Throwable) {
        val reason = t.message?.ifBlank { null }
            ?: t.cause?.message?.ifBlank { null }
            ?: "unknown SSH error"

        val hint = when {
            reason.contains("Auth fail", ignoreCase = true) -> "authentication failed (check SSH password/key and username)."
            reason.contains("Connection refused", ignoreCase = true) -> "connection refused (check host/port and sshd service)."
            reason.contains("timed out", ignoreCase = true) -> "connection timed out (check network/firewall)."
            else -> reason
        }

        formatBlock(
            title = "SSH Tree Error",
            body = "target=$user@$host:${input.sshPort}\nreason=$hint"
        )
    }

    rendered
}
