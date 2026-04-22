package com.koupper.providers.iac

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class IaCServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(IaCProvider::class, {
            TerraformIaCProvider(
                terraformCommand = env("TERRAFORM_COMMAND", required = false, default = "terraform"),
                timeoutSeconds = env("TERRAFORM_TIMEOUT_SECONDS", required = false, default = "300").toLong()
            )
        })
    }
}
