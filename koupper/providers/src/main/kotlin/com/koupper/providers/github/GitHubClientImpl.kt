package com.koupper.providers.github

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.os.env
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class GitHubClientImpl(
    private val token: String = env("GITHUB_TOKEN"),
    private val defaultOwner: String = env("GITHUB_OWNER", required = false, default = ""),
    private val defaultRepo: String = env("GITHUB_REPO", required = false, default = ""),
    private val apiUrl: String = env("GITHUB_API_URL", required = false, default = "https://api.github.com"),
    private val userAgent: String = env("GITHUB_USER_AGENT", required = false, default = "koupper-github-provider"),
    timeoutSeconds: Long = env("GITHUB_TIMEOUT_SECONDS", required = false, default = "30").toLongOrNull() ?: 30L
) : GitHubClient {

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    override fun createIssue(request: GitHubIssueRequest): GitHubIssueResponse {
        val (owner, repo) = resolveRepo(request.owner, request.repo)
        val body = mutableMapOf<String, Any>(
            "title" to request.title,
            "body" to request.body
        )

        if (request.labels.isNotEmpty()) body["labels"] = request.labels
        if (request.assignees.isNotEmpty()) body["assignees"] = request.assignees

        val map = requestJson(
            method = "POST",
            path = "/repos/$owner/$repo/issues",
            body = mapper.writeValueAsString(body)
        )

        return GitHubIssueResponse(
            number = (map["number"] as Number).toInt(),
            url = map["html_url"]?.toString().orEmpty(),
            title = map["title"]?.toString().orEmpty(),
            state = map["state"]?.toString().orEmpty()
        )
    }

    override fun createPullRequest(request: GitHubPullRequestRequest): GitHubPullRequestResponse {
        val (owner, repo) = resolveRepo(request.owner, request.repo)
        val body = mapOf(
            "title" to request.title,
            "head" to request.head,
            "base" to request.base,
            "body" to request.body,
            "draft" to request.draft
        )

        val map = requestJson(
            method = "POST",
            path = "/repos/$owner/$repo/pulls",
            body = mapper.writeValueAsString(body)
        )

        return GitHubPullRequestResponse(
            number = (map["number"] as Number).toInt(),
            url = map["html_url"]?.toString().orEmpty(),
            title = map["title"]?.toString().orEmpty(),
            state = map["state"]?.toString().orEmpty(),
            merged = map["merged"] as? Boolean ?: false
        )
    }

    override fun mergePullRequest(request: GitHubMergeRequest): GitHubPullRequestResponse {
        val (owner, repo) = resolveRepo(request.owner, request.repo)
        val payload = mutableMapOf<String, Any>(
            "merge_method" to request.mergeMethod
        )

        if (!request.commitTitle.isNullOrBlank()) payload["commit_title"] = request.commitTitle
        if (!request.commitMessage.isNullOrBlank()) payload["commit_message"] = request.commitMessage

        val map = requestJson(
            method = "PUT",
            path = "/repos/$owner/$repo/pulls/${request.pullNumber}/merge",
            body = mapper.writeValueAsString(payload)
        )

        return GitHubPullRequestResponse(
            number = request.pullNumber,
            url = map["url"]?.toString().orEmpty(),
            title = map["message"]?.toString().orEmpty(),
            state = if (map["merged"] == true) "merged" else "open",
            merged = map["merged"] as? Boolean ?: false
        )
    }

    override fun dispatchWorkflow(request: GitHubWorkflowDispatchRequest) {
        val (owner, repo) = resolveRepo(request.owner, request.repo)
        val body = mapOf(
            "ref" to request.ref,
            "inputs" to request.inputs
        )

        requestJson(
            method = "POST",
            path = "/repos/$owner/$repo/actions/workflows/${request.workflowId}/dispatches",
            body = mapper.writeValueAsString(body),
            acceptedStatus = setOf(204)
        )
    }

    override fun listWorkflowRuns(
        workflowId: String,
        branch: String?,
        event: String?,
        perPage: Int,
        owner: String?,
        repo: String?
    ): List<GitHubWorkflowRun> {
        val (resolvedOwner, resolvedRepo) = resolveRepo(owner, repo)

        val params = mutableListOf("per_page=$perPage")
        if (!branch.isNullOrBlank()) params += "branch=${encode(branch)}"
        if (!event.isNullOrBlank()) params += "event=${encode(event)}"
        val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"

        val map = requestJson(
            method = "GET",
            path = "/repos/$resolvedOwner/$resolvedRepo/actions/workflows/$workflowId/runs$query"
        )

        val runs = map["workflow_runs"] as? List<*> ?: emptyList<Any>()

        return runs.mapNotNull { raw ->
            val row = raw as? Map<*, *> ?: return@mapNotNull null
            GitHubWorkflowRun(
                id = (row["id"] as? Number)?.toLong() ?: return@mapNotNull null,
                name = row["name"]?.toString().orEmpty(),
                status = row["status"]?.toString().orEmpty(),
                conclusion = row["conclusion"]?.toString(),
                htmlUrl = row["html_url"]?.toString(),
                headBranch = row["head_branch"]?.toString(),
                event = row["event"]?.toString()
            )
        }
    }

    override fun getWorkflowRun(runId: Long, owner: String?, repo: String?): GitHubWorkflowRun {
        val (resolvedOwner, resolvedRepo) = resolveRepo(owner, repo)

        val map = requestJson(
            method = "GET",
            path = "/repos/$resolvedOwner/$resolvedRepo/actions/runs/$runId"
        )

        return GitHubWorkflowRun(
            id = (map["id"] as Number).toLong(),
            name = map["name"]?.toString().orEmpty(),
            status = map["status"]?.toString().orEmpty(),
            conclusion = map["conclusion"]?.toString(),
            htmlUrl = map["html_url"]?.toString(),
            headBranch = map["head_branch"]?.toString(),
            event = map["event"]?.toString()
        )
    }

    override fun listCheckRuns(ref: String, owner: String?, repo: String?): List<GitHubCheckRun> {
        val (resolvedOwner, resolvedRepo) = resolveRepo(owner, repo)

        val map = requestJson(
            method = "GET",
            path = "/repos/$resolvedOwner/$resolvedRepo/commits/${encode(ref)}/check-runs"
        )

        val rows = map["check_runs"] as? List<*> ?: emptyList<Any>()

        return rows.mapNotNull { raw ->
            val row = raw as? Map<*, *> ?: return@mapNotNull null
            GitHubCheckRun(
                name = row["name"]?.toString().orEmpty(),
                status = row["status"]?.toString().orEmpty(),
                conclusion = row["conclusion"]?.toString(),
                detailsUrl = row["details_url"]?.toString()
            )
        }
    }

    private fun requestJson(
        method: String,
        path: String,
        body: String? = null,
        acceptedStatus: Set<Int> = setOf(200, 201)
    ): Map<String, Any?> {
        val target = "${apiUrl.trimEnd('/')}$path"

        val builder = HttpRequest.newBuilder()
            .uri(URI(target))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)

        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in acceptedStatus) {
            val errorBody = response.body().ifBlank { "<empty response body>" }
            throw IllegalStateException("GitHub API request failed ($method $path): status=${response.statusCode()} body=$errorBody")
        }

        if (response.body().isBlank()) {
            return emptyMap()
        }

        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(response.body(), Map::class.java) as Map<String, Any?>
    }

    private fun resolveRepo(owner: String?, repo: String?): Pair<String, String> {
        val resolvedOwner = owner ?: defaultOwner
        val resolvedRepo = repo ?: defaultRepo

        if (resolvedOwner.isBlank() || resolvedRepo.isBlank()) {
            throw IllegalStateException(
                "GitHub owner/repo is required. Provide in request or configure GITHUB_OWNER and GITHUB_REPO."
            )
        }

        return resolvedOwner to resolvedRepo
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
