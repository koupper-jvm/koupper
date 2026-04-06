package com.koupper.providers.k8s

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class K8sServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(K8sProvider::class, {
            KubectlK8sProvider(
                kubectl = env("KUBECTL_COMMAND", required = false, default = "kubectl"),
                timeoutSeconds = env("KUBECTL_TIMEOUT_SECONDS", required = false, default = "120").toLong()
            )
        })
    }
}
