package com.koupper.providers.ssh

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SSHClientTest : AnnotationSpec() {

    // SSHClient.tree() is a default interface method with non-trivial parsing logic.
    // We test it by controlling what exec() returns, covering both output formats
    // (__KO_FIND__ and __KO_TREEPATHS__) without any real SSH connection.

    private fun fakeClient(execResponse: String): SSHClient = FakeSSHClient(execResponse)

    private fun okResult(stdout: String) = SSHCommandResult("cmd", 0, stdout, "", 0L)

    @Test
    fun `SSHCommandResult ok is true when exitCode is 0`() {
        val result = SSHCommandResult("ls", 0, "output", "", 100L)
        assertTrue(result.ok)
    }

    @Test
    fun `SSHCommandResult ok is false when exitCode is non-zero`() {
        val result = SSHCommandResult("ls", 1, "", "permission denied", 50L)
        assertFalse(result.ok)
    }

    @Test
    fun `tree with empty output returns empty nodes and empty source`() {
        val tree = fakeClient("").tree("/empty")
        assertTrue(tree.nodes.isEmpty())
        assertEquals("empty", tree.source)
    }

    @Test
    fun `tree with find output parses nodes and sets source to find`() {
        val findOutput = """
            __KO_FIND__
            d|/app/src
            f|/app/src/main.kt
            f|/app/build.gradle
        """.trimIndent()

        val tree = fakeClient(findOutput).tree("/app")
        assertEquals("find", tree.source)
        assertFalse(tree.nodes.isEmpty())
    }

    @Test
    fun `tree rendered output contains root path`() {
        val findOutput = "__KO_FIND__\nf|/project/README.md"
        val tree = fakeClient(findOutput).tree("/project")
        assertTrue(tree.rendered.contains("/project"))
    }

    @Test
    fun `tree with treepaths output sets source to tree`() {
        val treeOutput = """
            __KO_TREEPATHS__
            ./src/
            ./src/main.kt
            ./build.gradle
        """.trimIndent()

        val tree = fakeClient(treeOutput).tree(".")
        assertEquals("tree", tree.source)
    }

    @Test
    fun `tree with unrecognized format sets source to raw`() {
        val unknownOutput = "some random output\nline two"
        val tree = fakeClient(unknownOutput).tree("/misc")
        assertEquals("raw", tree.source)
        assertTrue(tree.nodes.isEmpty())
    }

    @Test
    fun `tree rootPath and depth are reflected in result`() {
        val findOutput = "__KO_FIND__\nf|/data/file.csv"
        val tree = fakeClient(findOutput).tree("/data", depth = 2)
        assertEquals("/data", tree.rootPath)
        assertEquals(2, tree.depth)
    }
}

private class FakeSSHClient(private val execStdout: String) : SSHClient {
    override val config = SSHConnectionConfig(host = "fake-host", username = "test-user")

    override fun exec(command: String, options: SSHExecOptions) =
        SSHCommandResult(command, 0, execStdout, "", 0L)

    override fun upload(localPath: String, remotePath: String, recursive: Boolean): SSHTransferResult =
        SSHTransferResult(localPath, remotePath, recursive, "scp $localPath $remotePath", 0, "", 0L)

    override fun download(remotePath: String, localPath: String, recursive: Boolean): SSHTransferResult =
        SSHTransferResult(remotePath, localPath, recursive, "scp $remotePath $localPath", 0, "", 0L)

    override fun exists(remotePath: String) = true
    override fun mkdir(remotePath: String, parents: Boolean) = exec("mkdir $remotePath")
    override fun readText(remotePath: String) = ""
    override fun writeText(remotePath: String, content: String) =
        SSHTransferResult(remotePath, remotePath, false, "write", 0, "", 0L)

    override fun roundTripEdit(request: SSHRoundTripRequest, transform: (String) -> String): SSHRoundTripResult =
        throw UnsupportedOperationException("not used in these tests")

    override fun syncWithRollback(request: SSHSyncRequest): SSHSyncResult =
        throw UnsupportedOperationException("not used in these tests")

    override fun applyTemplate(request: SSHTemplateRequest): SSHTemplateResult =
        throw UnsupportedOperationException("not used in these tests")
}
