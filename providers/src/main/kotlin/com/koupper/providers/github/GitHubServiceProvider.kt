package com.koupper.providers.github

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class GitHubServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(GitHubClient::class, {
            GitHubClientImpl()
        })
    }

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "github" to """
            import com.koupper.providers.github.GitHubClient
            fun github(): GitHubClient = com.koupper.container.app.getInstance(GitHubClient::class)
        """.trimIndent()
    )
}
