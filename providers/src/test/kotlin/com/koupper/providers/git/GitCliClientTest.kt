package com.koupper.providers.git

import io.kotest.core.spec.style.AnnotationSpec
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCliClientTest : AnnotationSpec() {

    private fun initRepo(): java.io.File {
        val dir = Files.createTempDirectory("koupper-git-test").toFile()
        Runtime.getRuntime().exec(arrayOf("git", "init", dir.absolutePath)).waitFor()
        Runtime.getRuntime().exec(arrayOf("git", "-C", dir.absolutePath, "config", "user.email", "test@koupper.com")).waitFor()
        Runtime.getRuntime().exec(arrayOf("git", "-C", dir.absolutePath, "config", "user.name", "Koupper Test")).waitFor()
        return dir
    }

    @Test
    fun `status returns ok result on valid repo`() {
        val repo = initRepo()
        val client = GitCliClient()
        val result = client.status(repo.absolutePath)
        assertEquals(0, result.exitCode)
        assertTrue(result.command.contains("status"))
    }

    @Test
    fun `log returns ok result on repo with commits`() {
        val repo = initRepo()
        val file = repo.resolve("test.txt").also { it.writeText("hello") }
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "add", file.name)).waitFor()
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "commit", "-m", "init")).waitFor()

        val result = GitCliClient().log(GitLogRequest(repoPath = repo.absolutePath, limit = 5))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("init"))
    }

    @Test
    fun `GitCommandResult timedOut defaults to false`() {
        val result = GitCommandResult(command = "git status", exitCode = 0, stdout = "ok", stderr = "")
        assertFalse(result.timedOut)
    }

    @Test
    fun `status on nonexistent path returns non-zero exit without throwing`() {
        val client = GitCliClient()
        val result = client.status("/tmp/nonexistent-koupper-repo-xyz")
        assertTrue(result.exitCode != 0 || result.timedOut)
    }
}
