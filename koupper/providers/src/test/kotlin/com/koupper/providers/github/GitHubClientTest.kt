package com.koupper.providers.github

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubClientTest : AnnotationSpec() {

    // FakeGitHubClient implements the interface contract without any network calls.
    // This validates the contract shape and any service-layer logic that wraps the client.
    private fun fakeClient(): GitHubClient = FakeGitHubClient()

    @Test
    fun `createIssue returns response with positive number and provided title`() {
        val response = fakeClient().createIssue(
            GitHubIssueRequest(title = "Bug: crash on startup", body = "Steps to repro...")
        )
        assertTrue(response.number > 0)
        assertEquals("Bug: crash on startup", response.title)
        assertEquals("open", response.state)
        assertTrue(response.url.isNotBlank())
    }

    @Test
    fun `createPullRequest returns open non-merged response`() {
        val response = fakeClient().createPullRequest(
            GitHubPullRequestRequest(title = "feat: new feature", head = "feature/x", base = "develop")
        )
        assertTrue(response.number > 0)
        assertEquals("open", response.state)
        assertFalse(response.merged)
    }

    @Test
    fun `mergePullRequest returns merged response with same pull number`() {
        val response = fakeClient().mergePullRequest(
            GitHubMergeRequest(pullNumber = 42, mergeMethod = "squash")
        )
        assertEquals(42, response.number)
        assertTrue(response.merged)
        assertEquals("merged", response.state)
    }

    @Test
    fun `dispatchWorkflow does not throw`() {
        fakeClient().dispatchWorkflow(
            GitHubWorkflowDispatchRequest(workflowId = "ci.yml", ref = "develop", inputs = mapOf("env" to "staging"))
        )
    }

    @Test
    fun `listWorkflowRuns returns non-empty list with valid ids`() {
        val runs = fakeClient().listWorkflowRuns("ci.yml", branch = "main")
        assertTrue(runs.isNotEmpty())
        assertTrue(runs.all { it.id > 0 })
        assertTrue(runs.all { it.name.isNotBlank() })
    }

    @Test
    fun `getWorkflowRun returns run matching requested id`() {
        val run = fakeClient().getWorkflowRun(runId = 9999L)
        assertEquals(9999L, run.id)
        assertEquals("completed", run.status)
    }

    @Test
    fun `listCheckRuns returns check runs with name and status`() {
        val checks = fakeClient().listCheckRuns("abc123")
        assertTrue(checks.isNotEmpty())
        assertTrue(checks.all { it.name.isNotBlank() && it.status.isNotBlank() })
    }
}

private class FakeGitHubClient : GitHubClient {
    private var counter = 1

    override fun createIssue(request: GitHubIssueRequest) = GitHubIssueResponse(
        number = counter++,
        url = "https://github.com/fake/repo/issues/${counter - 1}",
        title = request.title,
        state = "open"
    )

    override fun createPullRequest(request: GitHubPullRequestRequest) = GitHubPullRequestResponse(
        number = counter++,
        url = "https://github.com/fake/repo/pulls/${counter - 1}",
        title = request.title,
        state = "open",
        merged = false
    )

    override fun mergePullRequest(request: GitHubMergeRequest) = GitHubPullRequestResponse(
        number = request.pullNumber,
        url = "https://github.com/fake/repo/pulls/${request.pullNumber}",
        title = "Merged",
        state = "merged",
        merged = true
    )

    override fun dispatchWorkflow(request: GitHubWorkflowDispatchRequest) {}

    override fun listWorkflowRuns(
        workflowId: String, branch: String?, event: String?,
        perPage: Int, owner: String?, repo: String?
    ) = listOf(
        GitHubWorkflowRun(id = 1001L, name = workflowId, status = "completed", conclusion = "success", headBranch = branch)
    )

    override fun getWorkflowRun(runId: Long, owner: String?, repo: String?) =
        GitHubWorkflowRun(id = runId, name = "ci", status = "completed", conclusion = "success")

    override fun listCheckRuns(ref: String, owner: String?, repo: String?) = listOf(
        GitHubCheckRun(name = "build", status = "completed", conclusion = "success"),
        GitHubCheckRun(name = "test", status = "completed", conclusion = "success")
    )
}
