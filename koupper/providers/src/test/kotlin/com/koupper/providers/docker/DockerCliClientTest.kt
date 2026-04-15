package com.koupper.providers.docker

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockerCliClientTest : AnnotationSpec() {

    private fun mockClient(
        runner: (List<String>) -> DockerCommandResult
    ) = DockerCliClient(commandRunner = runner)

    @Test
    fun `build constructs correct docker build command`() {
        var captured = emptyList<String>()
        val client = mockClient { args -> captured = args; DockerCommandResult(args.joinToString(" "), 0, "built", "") }

        client.build(DockerBuildRequest(contextPath = ".", tag = "myapp:latest", noCache = true))

        assertTrue(captured.contains("build"))
        assertTrue(captured.contains("--no-cache"))
        assertTrue(captured.contains("-t"))
        assertTrue(captured.contains("myapp:latest"))
    }

    @Test
    fun `run constructs correct docker run command`() {
        var captured = emptyList<String>()
        val client = mockClient { args -> captured = args; DockerCommandResult(args.joinToString(" "), 0, "running", "") }

        client.run(DockerRunRequest(image = "nginx:alpine", name = "web", detach = true, ports = listOf("8080:80")))

        assertTrue(captured.contains("run"))
        assertTrue(captured.contains("-d"))
        assertTrue(captured.contains("--name"))
        assertTrue(captured.contains("web"))
        assertTrue(captured.contains("-p"))
        assertTrue(captured.contains("8080:80"))
        assertTrue(captured.contains("nginx:alpine"))
    }

    @Test
    fun `stop constructs correct docker stop command`() {
        var captured = emptyList<String>()
        val client = mockClient { args -> captured = args; DockerCommandResult(args.joinToString(" "), 0, "", "") }

        client.stop("my-container")

        assertTrue(captured.contains("stop"))
        assertTrue(captured.contains("my-container"))
    }

    @Test
    fun `listContainers with all=true includes -a flag`() {
        var captured = emptyList<String>()
        val client = mockClient { args -> captured = args; DockerCommandResult(args.joinToString(" "), 0, "", "") }

        client.listContainers(all = true)

        assertTrue(captured.contains("ps"))
        assertTrue(captured.contains("-a"))
    }

    @Test
    fun `commandRunner result is returned directly`() {
        val client = mockClient { DockerCommandResult("docker build .", 0, "sha256:abc", "") }
        val result = client.build(DockerBuildRequest(contextPath = "."))
        assertEquals(0, result.exitCode)
        assertEquals("sha256:abc", result.stdout)
    }

    @Test
    fun `build includes dockerfile flag when specified`() {
        var captured = emptyList<String>()
        val client = mockClient { args -> captured = args; DockerCommandResult(args.joinToString(" "), 0, "", "") }

        client.build(DockerBuildRequest(contextPath = ".", dockerfile = "Dockerfile.prod"))

        assertTrue(captured.contains("-f"))
        assertTrue(captured.contains("Dockerfile.prod"))
    }
}
