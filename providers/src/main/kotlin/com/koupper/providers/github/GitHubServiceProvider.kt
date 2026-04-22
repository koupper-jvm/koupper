package com.koupper.providers.github

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class GitHubServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(GitHubClient::class, {
            GitHubClientImpl()
        })
    }
}
