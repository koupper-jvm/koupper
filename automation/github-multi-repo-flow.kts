import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.github.GitHubCheckRun
import com.koupper.providers.github.GitHubClient
import com.koupper.providers.github.GitHubIssueRequest
import com.koupper.providers.github.GitHubMergeRequest
import com.koupper.providers.github.GitHubPullRequestRequest
import com.koupper.providers.github.GitHubWorkflowDispatchRequest

data class IssuePlan(
    val title: String,
    val body: String,
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList()
)

data class PullRequestPlan(
    val create: Boolean = false,
    val title: String = "",
    val body: String = "",
    val head: String = "",
    val base: String = "develop",
    val number: Int? = null,
    val merge: Boolean = false,
    val mergeMethod: String = "merge"
)

data class ChecksPlan(
    val wait: Boolean = false,
    val ref: String = "",
    val required: List<String> = emptyList(),
    val timeoutSeconds: Long = 900,
    val pollIntervalSeconds: Long = 15
)

data class WorkflowPlan(
    val dispatch: Boolean = false,
    val workflowId: String = "",
    val ref: String = "develop",
    val inputs: Map<String, String> = emptyMap(),
    val wait: Boolean = false,
    val timeoutSeconds: Long = 1800,
    val pollIntervalSeconds: Long = 15
)

data class RepoFlowTarget(
    val name: String,
    val enabled: Boolean = true,
    val owner: String,
    val repo: String,
    val pullRequest: PullRequestPlan = PullRequestPlan(),
    val checks: ChecksPlan = ChecksPlan(),
    val workflow: WorkflowPlan = WorkflowPlan(),
    val issues: List<IssuePlan> = emptyList()
)

data class MultiRepoFlowInput(
    val dryRun: Boolean = true,
    val continueOnError: Boolean = false,
    val targets: List<RepoFlowTarget> = emptyList()
)

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

private fun isTerminal(status: String): Boolean = status.equals("completed", ignoreCase = true)

private fun summarizeChecks(checks: List<GitHubCheckRun>): String {
    if (checks.isEmpty()) return "no check runs found"
    return checks.joinToString(" | ") { "${it.name}:${it.status}/${it.conclusion ?: "n/a"}" }
}

private fun runTarget(
    gh: GitHubClient,
    dryRun: Boolean,
    target: RepoFlowTarget,
    step: (String) -> Unit
): Map<String, Any?> {
    val output = linkedMapOf<String, Any?>(
        "name" to target.name,
        "owner" to target.owner,
        "repo" to target.repo
    )

    var pullNumber = target.pullRequest.number

    if (target.pullRequest.create) {
        if (dryRun) {
            step("[dry-run][${target.name}] create PR '${target.pullRequest.title}' from ${target.pullRequest.head} to ${target.pullRequest.base}")
            pullNumber = pullNumber ?: -1
        } else {
            val pr = gh.createPullRequest(
                GitHubPullRequestRequest(
                    title = target.pullRequest.title,
                    body = target.pullRequest.body,
                    head = target.pullRequest.head,
                    base = target.pullRequest.base,
                    owner = target.owner,
                    repo = target.repo
                )
            )
            pullNumber = pr.number
            output["pullRequestUrl"] = pr.url
            step("[ok][${target.name}] PR created: #${pr.number} ${pr.url}")
        }
    }

    if (target.checks.wait) {
        val ref = if (target.checks.ref.isNotBlank()) target.checks.ref else "refs/heads/${target.pullRequest.head}"
        if (dryRun) {
            step("[dry-run][${target.name}] wait checks for ref '$ref' required=${target.checks.required}")
        } else {
            val deadline = nowSeconds() + target.checks.timeoutSeconds
            var passed = false

            while (nowSeconds() < deadline) {
                val checks = gh.listCheckRuns(ref, owner = target.owner, repo = target.repo)
                step("[checks][${target.name}] ${summarizeChecks(checks)}")

                val requiredChecks = if (target.checks.required.isEmpty()) checks else {
                    checks.filter { run -> target.checks.required.any { it.equals(run.name, ignoreCase = true) } }
                }

                if (requiredChecks.isNotEmpty() && requiredChecks.all { isTerminal(it.status) }) {
                    val anyFailure = requiredChecks.any {
                        val conclusion = it.conclusion?.lowercase()
                        conclusion == "failure" || conclusion == "cancelled" || conclusion == "timed_out" || conclusion == "startup_failure"
                    }

                    if (anyFailure) {
                        error("Required checks failed for ${target.name} on ref '$ref'")
                    }

                    passed = true
                    break
                }

                Thread.sleep(target.checks.pollIntervalSeconds * 1000)
            }

            if (!passed) error("Timed out waiting for checks for ${target.name} on '$ref'")
            step("[ok][${target.name}] required checks completed successfully")
        }
    }

    if (target.workflow.dispatch) {
        if (dryRun) {
            step("[dry-run][${target.name}] dispatch workflow ${target.workflow.workflowId} on ref ${target.workflow.ref}")
        } else {
            gh.dispatchWorkflow(
                GitHubWorkflowDispatchRequest(
                    workflowId = target.workflow.workflowId,
                    ref = target.workflow.ref,
                    inputs = target.workflow.inputs,
                    owner = target.owner,
                    repo = target.repo
                )
            )
            step("[ok][${target.name}] workflow dispatch requested for ${target.workflow.workflowId}")
        }
    }

    if (target.workflow.wait) {
        if (dryRun) {
            step("[dry-run][${target.name}] wait latest workflow run for ${target.workflow.workflowId} on ${target.workflow.ref}")
        } else {
            val deadline = nowSeconds() + target.workflow.timeoutSeconds
            var foundRun = false

            while (nowSeconds() < deadline) {
                val runs = gh.listWorkflowRuns(
                    workflowId = target.workflow.workflowId,
                    branch = target.workflow.ref,
                    owner = target.owner,
                    repo = target.repo,
                    perPage = 10
                )

                val first = runs.firstOrNull()
                if (first != null) {
                    foundRun = true
                    val run = gh.getWorkflowRun(first.id, owner = target.owner, repo = target.repo)
                    step("[workflow][${target.name}] run=${run.id} status=${run.status} conclusion=${run.conclusion}")

                    if (isTerminal(run.status)) {
                        if (!run.conclusion.equals("success", ignoreCase = true)) {
                            error("Workflow ${run.id} for ${target.name} finished with conclusion=${run.conclusion}")
                        }
                        output["workflowRunId"] = run.id
                        output["workflowRunUrl"] = run.htmlUrl
                        step("[ok][${target.name}] workflow run ${run.id} succeeded")
                        break
                    }
                }

                Thread.sleep(target.workflow.pollIntervalSeconds * 1000)
            }

            if (!foundRun) {
                error("No workflow run found for ${target.name}: workflow=${target.workflow.workflowId} ref=${target.workflow.ref}")
            }
        }
    }

    if (target.pullRequest.merge) {
        if (pullNumber == null || pullNumber <= 0) {
            error("${target.name}: pullRequest.merge=true requires a valid pull request number")
        }

        if (dryRun) {
            step("[dry-run][${target.name}] merge PR #$pullNumber using ${target.pullRequest.mergeMethod}")
        } else {
            val merged = gh.mergePullRequest(
                GitHubMergeRequest(
                    pullNumber = pullNumber,
                    mergeMethod = target.pullRequest.mergeMethod,
                    owner = target.owner,
                    repo = target.repo
                )
            )

            if (!merged.merged) {
                error("${target.name}: pull request #$pullNumber was not merged")
            }

            step("[ok][${target.name}] PR #$pullNumber merged")
        }
    }

    if (target.issues.isNotEmpty()) {
        if (dryRun) {
            step("[dry-run][${target.name}] create ${target.issues.size} issues")
        } else {
            val created = mutableListOf<Map<String, Any?>>()
            target.issues.forEach { issue ->
                val createdIssue = gh.createIssue(
                    GitHubIssueRequest(
                        title = issue.title,
                        body = issue.body,
                        labels = issue.labels,
                        assignees = issue.assignees,
                        owner = target.owner,
                        repo = target.repo
                    )
                )

                created += mapOf(
                    "number" to createdIssue.number,
                    "url" to createdIssue.url,
                    "title" to createdIssue.title
                )
            }
            output["issues"] = created
            step("[ok][${target.name}] created ${created.size} issues")
        }
    }

    return output
}

@Export
val runGitHubMultiRepoFlow: (MultiRepoFlowInput) -> Map<String, Any?> = { input ->
    val gh = app.getInstance(GitHubClient::class)
    val steps = mutableListOf<String>()
    val targetResults = mutableListOf<Map<String, Any?>>()
    val targetErrors = mutableListOf<Map<String, String>>()

    fun step(message: String) {
        steps.add(message)
        println(message)
    }

    val enabledTargets = input.targets.filter { it.enabled }
    step("[start] multi-repo flow targets=${enabledTargets.size} dryRun=${input.dryRun}")

    enabledTargets.forEach { target ->
        try {
            step("[target] starting ${target.name} (${target.owner}/${target.repo})")
            targetResults += runTarget(gh, input.dryRun, target, ::step)
            step("[target] completed ${target.name}")
        } catch (e: Exception) {
            step("[error][${target.name}] ${e.message}")
            targetErrors += mapOf("target" to target.name, "message" to (e.message ?: "unknown error"))
            if (!input.continueOnError) throw e
        }
    }

    mapOf(
        "dryRun" to input.dryRun,
        "continueOnError" to input.continueOnError,
        "steps" to steps,
        "results" to targetResults,
        "errors" to targetErrors
    )
}
