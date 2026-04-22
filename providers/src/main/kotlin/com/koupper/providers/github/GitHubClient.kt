package com.koupper.providers.github

data class GitHubIssueRequest(
    val title: String,
    val body: String,
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val owner: String? = null,
    val repo: String? = null
)

data class GitHubIssueResponse(
    val number: Int,
    val url: String,
    val title: String,
    val state: String
)

data class GitHubPullRequestRequest(
    val title: String,
    val head: String,
    val base: String,
    val body: String = "",
    val draft: Boolean = false,
    val owner: String? = null,
    val repo: String? = null
)

data class GitHubPullRequestResponse(
    val number: Int,
    val url: String,
    val title: String,
    val state: String,
    val merged: Boolean = false
)

data class GitHubMergeRequest(
    val pullNumber: Int,
    val mergeMethod: String = "merge",
    val commitTitle: String? = null,
    val commitMessage: String? = null,
    val owner: String? = null,
    val repo: String? = null
)

data class GitHubWorkflowDispatchRequest(
    val workflowId: String,
    val ref: String,
    val inputs: Map<String, String> = emptyMap(),
    val owner: String? = null,
    val repo: String? = null
)

data class GitHubCheckRun(
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val detailsUrl: String? = null
)

data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val htmlUrl: String? = null,
    val headBranch: String? = null,
    val event: String? = null
)

interface GitHubClient {
    fun createIssue(request: GitHubIssueRequest): GitHubIssueResponse
    fun createPullRequest(request: GitHubPullRequestRequest): GitHubPullRequestResponse
    fun mergePullRequest(request: GitHubMergeRequest): GitHubPullRequestResponse
    fun dispatchWorkflow(request: GitHubWorkflowDispatchRequest)
    fun listWorkflowRuns(
        workflowId: String,
        branch: String? = null,
        event: String? = null,
        perPage: Int = 20,
        owner: String? = null,
        repo: String? = null
    ): List<GitHubWorkflowRun>

    fun getWorkflowRun(
        runId: Long,
        owner: String? = null,
        repo: String? = null
    ): GitHubWorkflowRun

    fun listCheckRuns(
        ref: String,
        owner: String? = null,
        repo: String? = null
    ): List<GitHubCheckRun>
}
