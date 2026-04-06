package com.koupper.providers.secrets

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class SecretsServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(SecretsClient::class, {
            LocalSecretsClient(
                SecretsConfig(
                    filePath = env("SECRETS_FILE", required = false, default = ".koupper-secrets.json"),
                    envPrefix = env("SECRETS_ENV_PREFIX", required = false, default = "SECRET_"),
                    persistWrites = env("SECRETS_PERSIST_WRITES", required = false, default = "true")
                        .equals("true", ignoreCase = true),
                    requireFile = env("SECRETS_REQUIRE_FILE", required = false, default = "false")
                        .equals("true", ignoreCase = true)
                )
            )
        })
    }
}
