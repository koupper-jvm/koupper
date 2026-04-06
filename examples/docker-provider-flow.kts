/**
 * Docker Provider Operations Demo
 *
 * Purpose:
 * - Execute one Docker operation per run using the Docker provider.
 *
 * Typical runs:
 * - koupper run examples/docker-provider-flow.kts '{"action":"ps"}'
 * - koupper run examples/docker-provider-flow.kts '{"action":"compose-up","composeFile":"docker-compose.yml"}'
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.docker.DockerBuildRequest
import com.koupper.providers.docker.DockerClient
import com.koupper.providers.docker.DockerComposeRequest
import com.koupper.providers.docker.DockerExecRequest
import com.koupper.providers.docker.DockerRunRequest

data class Input(
    val action: String,
    val image: String? = null,
    val container: String? = null,
    val command: List<String> = emptyList(),
    val composeFile: String = "docker-compose.yml",
    val composeProject: String? = null,
    val composeServices: List<String> = emptyList(),
    val contextPath: String = ".",
    val dockerfile: String? = null,
    val tag: String? = null,
    val tail: Int = 200,
    val ports: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val name: String? = null
)

@Export
val dockerOps: (Input) -> Map<String, Any?> = { input ->
    val docker = app.getInstance(DockerClient::class)

    val result = when (input.action.lowercase()) {
        "ps" -> docker.listContainers(all = false)
        "ps-all" -> docker.listContainers(all = true)
        "build" -> docker.build(
            DockerBuildRequest(
                contextPath = input.contextPath,
                dockerfile = input.dockerfile,
                tag = input.tag
            )
        )

        "run" -> docker.run(
            DockerRunRequest(
                image = input.image ?: error("image is required for run"),
                name = input.name,
                command = input.command,
                env = input.env,
                ports = input.ports,
                volumes = input.volumes
            )
        )

        "stop" -> docker.stop(input.container ?: error("container is required for stop"))
        "logs" -> docker.logs(input.container ?: error("container is required for logs"), tail = input.tail)
        "exec" -> docker.exec(
            DockerExecRequest(
                container = input.container ?: error("container is required for exec"),
                command = input.command.ifEmpty { error("command is required for exec") },
                env = input.env
            )
        )

        "compose-up" -> docker.composeUp(
            DockerComposeRequest(
                file = input.composeFile,
                project = input.composeProject,
                services = input.composeServices
            )
        )

        "compose-down" -> docker.composeDown(
            DockerComposeRequest(
                file = input.composeFile,
                project = input.composeProject,
                services = input.composeServices
            )
        )

        else -> error("Unsupported action '${input.action}'. Use ps, ps-all, build, run, stop, logs, exec, compose-up, compose-down.")
    }

    mapOf(
        "ok" to (result.exitCode == 0),
        "action" to input.action,
        "command" to result.command,
        "exitCode" to result.exitCode,
        "stdout" to result.stdout,
        "stderr" to result.stderr
    )
}
