package com.koupper.providers

import com.koupper.providers.ai.AIServiceProvider
import com.koupper.providers.aillmops.AILlmOpsServiceProvider
import com.koupper.providers.aws.dynamo.AwsServiceProvider
import com.koupper.providers.aws.s3.AwsS3ServiceProvider
import com.koupper.providers.crypto.CryptoServiceProvider
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.docker.DockerServiceProvider
import com.koupper.providers.files.FileServiceProvider
import com.koupper.providers.hashing.HasherServiceProvider
import com.koupper.providers.github.GitHubServiceProvider
import com.koupper.providers.git.GitServiceProvider
import com.koupper.providers.http.HttpServiceProvider
import com.koupper.providers.iac.IaCServiceProvider
import com.koupper.providers.jwt.JWTServiceProvider
import com.koupper.providers.k8s.K8sServiceProvider
import com.koupper.providers.logger.LoggerServiceProvider
import com.koupper.providers.mcp.MCPServiceProvider
import com.koupper.providers.mailing.SenderServiceProvider
import com.koupper.providers.notifications.NotificationsServiceProvider
import com.koupper.providers.observability.ObservabilityServiceProvider
import com.koupper.providers.queueops.QueueOpsServiceProvider
import com.koupper.providers.rss.RSSServiceProvider
import com.koupper.providers.runtime.router.RuntimeRouterServiceProvider
import com.koupper.providers.secrets.SecretsServiceProvider
import com.koupper.providers.ssh.SSHServiceProvider
import com.koupper.providers.templates.TemplateServiceProvider
import com.koupper.providers.vectordb.VectorDbServiceProvider
import kotlin.reflect.KClass

val launchProcess: (() -> Unit) -> Thread = { callback ->
    val thread = Thread {
        callback()
    }

    thread.start()

    waitFor(thread).join()

    thread
}

val waitFor: (Thread) -> Thread = { thread ->
    val loading = Thread {
        val a = arrayOf("⁘", "⁙", "⁚", "⁛", "⁜")

        while (thread.isAlive) {
            print("building ${a.random()}")
            Thread.sleep(200L)
            print("\r")
        }
    }

    loading.start()

    loading
}

class ServiceProviderManager {
    fun listProviders(): List<KClass<*>> {
        return listOf(
            DBServiceProvider::class,
            DockerServiceProvider::class,
            SenderServiceProvider::class,
            LoggerServiceProvider::class,
            MCPServiceProvider::class,
            NotificationsServiceProvider::class,
            ObservabilityServiceProvider::class,
            QueueOpsServiceProvider::class,
            HttpServiceProvider::class,
            IaCServiceProvider::class,
            FileServiceProvider::class,
            JWTServiceProvider::class,
            K8sServiceProvider::class,
            CryptoServiceProvider::class,
            AwsServiceProvider::class,
            AwsS3ServiceProvider::class,
            HasherServiceProvider::class,
            GitServiceProvider::class,
            GitHubServiceProvider::class,
            AIServiceProvider::class,
            AILlmOpsServiceProvider::class,
            TemplateServiceProvider::class,
            VectorDbServiceProvider::class,
            RSSServiceProvider::class,
            RuntimeRouterServiceProvider::class,
            SecretsServiceProvider::class,
            SSHServiceProvider::class,
        )
    }
}
