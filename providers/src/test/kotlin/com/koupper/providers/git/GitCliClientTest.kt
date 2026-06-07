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

    private fun commitFile(repo: java.io.File, name: String = "test.txt", content: String = "hello") {
        val file = repo.resolve(name).also { it.writeText(content) }
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "add", file.name)).waitFor()
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "commit", "-m", "add $name")).waitFor()
    }

    // ── existing ──────────────────────────────────────────────────────────────

    @Test
    fun `status returns ok result on valid repo`() {
        val repo = initRepo()
        val result = GitCliClient().status(repo.absolutePath)
        assertEquals(0, result.exitCode)
        assertTrue(result.command.contains("status"))
    }

    @Test
    fun `log returns ok result on repo with commits`() {
        val repo = initRepo()
        commitFile(repo)
        val result = GitCliClient().log(GitLogRequest(repoPath = repo.absolutePath, limit = 5))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("add test.txt"))
    }

    @Test
    fun `GitCommandResult timedOut defaults to false`() {
        val result = GitCommandResult(command = "git status", exitCode = 0, stdout = "ok", stderr = "")
        assertFalse(result.timedOut)
    }

    @Test
    fun `status on nonexistent path returns non-zero exit without throwing`() {
        val result = GitCliClient().status("/tmp/nonexistent-koupper-repo-xyz")
        assertTrue(result.exitCode != 0 || result.timedOut)
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    fun `add with empty files stages all changes`() {
        val repo = initRepo()
        repo.resolve("a.txt").writeText("content")
        val result = GitCliClient().add(GitAddRequest(repoPath = repo.absolutePath))
        assertEquals(0, result.exitCode)
        val staged = Runtime.getRuntime().exec(
            arrayOf("git", "-C", repo.absolutePath, "diff", "--cached", "--name-only")
        ).inputStream.bufferedReader().readText().trim()
        assertTrue(staged.contains("a.txt"))
    }

    @Test
    fun `add with specific files stages only those files`() {
        val repo = initRepo()
        repo.resolve("a.txt").writeText("content-a")
        repo.resolve("b.txt").writeText("content-b")
        val result = GitCliClient().add(GitAddRequest(repoPath = repo.absolutePath, files = listOf("a.txt")))
        assertEquals(0, result.exitCode)
        val staged = Runtime.getRuntime().exec(
            arrayOf("git", "-C", repo.absolutePath, "diff", "--cached", "--name-only")
        ).inputStream.bufferedReader().readText().trim()
        assertTrue(staged.contains("a.txt"))
        assertFalse(staged.contains("b.txt"))
    }

    // ── blame ─────────────────────────────────────────────────────────────────

    @Test
    fun `blame returns line attribution after commit`() {
        val repo = initRepo()
        commitFile(repo, "blame.txt", "line one\nline two\nline three")
        val result = GitCliClient().blame(GitBlameRequest(repoPath = repo.absolutePath, file = "blame.txt"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("Koupper Test"))
    }

    @Test
    fun `blame with line range returns only requested lines`() {
        val repo = initRepo()
        commitFile(repo, "range.txt", "alpha\nbeta\ngamma\ndelta")
        val result = GitCliClient().blame(
            GitBlameRequest(repoPath = repo.absolutePath, file = "range.txt", lineStart = 2, lineEnd = 3)
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.command.contains("-L"))
        assertTrue(result.stdout.contains("beta") || result.stdout.contains("gamma"))
    }

    // ── currentBranch ─────────────────────────────────────────────────────────

    @Test
    fun `currentBranch returns branch name after first commit`() {
        val repo = initRepo()
        commitFile(repo)
        val result = GitCliClient().currentBranch(repo.absolutePath)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.isNotBlank())
    }

    // ── listBranches ──────────────────────────────────────────────────────────

    @Test
    fun `listBranches includes current branch`() {
        val repo = initRepo()
        commitFile(repo)
        val result = GitCliClient().listBranches(repo.absolutePath)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.isNotBlank())
    }

    // ── deleteBranch ──────────────────────────────────────────────────────────

    @Test
    fun `deleteBranch removes a merged branch`() {
        val repo = initRepo()
        commitFile(repo)
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "branch", "to-delete")).waitFor()
        val result = GitCliClient().deleteBranch(repo.absolutePath, "to-delete", force = false)
        assertEquals(0, result.exitCode)
        val branches = GitCliClient().listBranches(repo.absolutePath)
        assertFalse(branches.stdout.contains("to-delete"))
    }

    @Test
    fun `deleteBranch with force removes unmerged branch`() {
        val repo = initRepo()
        commitFile(repo)
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "checkout", "-b", "unmerged")).waitFor()
        commitFile(repo, "extra.txt", "extra")
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "checkout", "-")).waitFor()
        val result = GitCliClient().deleteBranch(repo.absolutePath, "unmerged", force = true)
        assertEquals(0, result.exitCode)
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset soft undoes commit keeping changes staged`() {
        val repo = initRepo()
        commitFile(repo, "first.txt", "v1")
        commitFile(repo, "second.txt", "v2")
        val result = GitCliClient().reset(GitResetRequest(repoPath = repo.absolutePath, mode = ResetMode.SOFT, ref = "HEAD~1"))
        assertEquals(0, result.exitCode)
        val staged = Runtime.getRuntime().exec(
            arrayOf("git", "-C", repo.absolutePath, "diff", "--cached", "--name-only")
        ).inputStream.bufferedReader().readText().trim()
        assertTrue(staged.contains("second.txt"))
    }

    @Test
    fun `reset mixed undoes commit leaving changes unstaged`() {
        val repo = initRepo()
        commitFile(repo, "a.txt", "v1")
        commitFile(repo, "b.txt", "v2")
        val result = GitCliClient().reset(GitResetRequest(repoPath = repo.absolutePath, mode = ResetMode.MIXED, ref = "HEAD~1"))
        assertEquals(0, result.exitCode)
        val staged = Runtime.getRuntime().exec(
            arrayOf("git", "-C", repo.absolutePath, "diff", "--cached", "--quiet")
        ).waitFor()
        assertEquals(0, staged)
    }

    // ── stash / stashPop ──────────────────────────────────────────────────────

    @Test
    fun `stash saves uncommitted changes and stashPop restores them`() {
        val repo = initRepo()
        commitFile(repo)
        repo.resolve("dirty.txt").writeText("dirty content")
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo.absolutePath, "add", "dirty.txt")).waitFor()

        val stashResult = GitCliClient().stash(GitStashRequest(repoPath = repo.absolutePath, message = "wip"))
        assertEquals(0, stashResult.exitCode)
        assertFalse(repo.resolve("dirty.txt").exists())

        val popResult = GitCliClient().stashPop(repo.absolutePath)
        assertEquals(0, popResult.exitCode)
        assertTrue(repo.resolve("dirty.txt").exists())
    }

    // ── push / pull / fetch (graceful failure without remote) ─────────────────

    @Test
    fun `push without remote fails gracefully with non-zero exit`() {
        val repo = initRepo()
        commitFile(repo)
        val result = GitCliClient().push(GitPushRequest(repoPath = repo.absolutePath))
        assertTrue(result.exitCode != 0)
        assertFalse(result.timedOut)
    }

    @Test
    fun `pull without remote fails gracefully with non-zero exit`() {
        val repo = initRepo()
        commitFile(repo)
        val result = GitCliClient().pull(GitPullRequest(repoPath = repo.absolutePath))
        assertTrue(result.exitCode != 0)
        assertFalse(result.timedOut)
    }

    @Test
    fun `fetch without remote fails gracefully with non-zero exit`() {
        val repo = initRepo()
        commitFile(repo)
        val result = GitCliClient().fetch(GitFetchRequest(repoPath = repo.absolutePath))
        assertTrue(result.exitCode != 0)
        assertFalse(result.timedOut)
    }

    @Test
    fun `push and pull work via local bare remote`() {
        val bare = Files.createTempDirectory("koupper-git-bare").toFile()
        Runtime.getRuntime().exec(arrayOf("git", "init", "--bare", bare.absolutePath)).waitFor()

        val repo = initRepo()
        commitFile(repo)
        Runtime.getRuntime().exec(
            arrayOf("git", "-C", repo.absolutePath, "remote", "add", "origin", bare.absolutePath)
        ).waitFor()

        val branch = GitCliClient().currentBranch(repo.absolutePath).stdout.trim()
        val pushResult = GitCliClient().push(GitPushRequest(repoPath = repo.absolutePath, branch = branch, setUpstream = true))
        assertEquals(0, pushResult.exitCode)

        val repo2 = Files.createTempDirectory("koupper-git-clone").toFile()
        Runtime.getRuntime().exec(arrayOf("git", "clone", bare.absolutePath, repo2.absolutePath)).waitFor()
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo2.absolutePath, "config", "user.email", "test@koupper.com")).waitFor()
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo2.absolutePath, "config", "user.name", "Koupper Test")).waitFor()

        commitFile(repo2, "from-clone.txt", "new content")
        Runtime.getRuntime().exec(arrayOf("git", "-C", repo2.absolutePath, "push", "origin")).waitFor()

        val pullResult = GitCliClient().pull(GitPullRequest(repoPath = repo.absolutePath))
        assertEquals(0, pullResult.exitCode)
        assertTrue(repo.resolve("from-clone.txt").exists())
    }
}
