import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.ssh.SSHClient
import com.koupper.providers.ssh.SSHRoundTripRequest
import com.koupper.providers.ssh.SSHSyncRequest
import com.koupper.providers.ssh.SSHTemplateRequest

data class Input(
    val mode: String,
    val remotePath: String,
    val localWorkingDir: String = ".koupper/ssh-work",
    val localFileName: String = "remote-file.txt",
    val appendLine: String = "",
    val postUploadCommands: List<String> = emptyList(),
    val remoteDir: String = "",
    val localDir: String = "",
    val verifyCommands: List<String> = emptyList(),
    val template: String = "",
    val variables: Map<String, String> = emptyMap(),
    val postWriteCommands: List<String> = emptyList()
)

@Export
val sshFlow: (Input) -> Map<String, Any?> = { input ->
    val ssh = app.getInstance(SSHClient::class)

    when (input.mode.lowercase()) {
        "roundtrip-edit" -> {
            val result = ssh.roundTripEdit(
                request = SSHRoundTripRequest(
                    remotePath = input.remotePath,
                    localWorkingDir = input.localWorkingDir,
                    localFileName = input.localFileName,
                    backupRemote = true,
                    postUploadCommands = input.postUploadCommands
                )
            ) { original ->
                val suffix = if (input.appendLine.isBlank()) "" else "\n${input.appendLine}\n"
                original + suffix
            }

            mapOf(
                "ok" to true,
                "flow" to "roundtrip-edit",
                "localPath" to result.localPath,
                "downloadOk" to result.download.ok,
                "uploadOk" to result.upload.ok,
                "postUploadCount" to result.postUpload.size
            )
        }

        "download-dir" -> {
            val result = ssh.download(
                remotePath = input.remoteDir.ifBlank { error("remoteDir is required for download-dir") },
                localPath = input.localDir.ifBlank { error("localDir is required for download-dir") },
                recursive = true
            )

            mapOf(
                "ok" to result.ok,
                "flow" to "download-dir",
                "exitCode" to result.exitCode,
                "durationMs" to result.durationMs
            )
        }

        "upload-dir" -> {
            val result = ssh.upload(
                localPath = input.localDir.ifBlank { error("localDir is required for upload-dir") },
                remotePath = input.remoteDir.ifBlank { error("remoteDir is required for upload-dir") },
                recursive = true
            )

            mapOf(
                "ok" to result.ok,
                "flow" to "upload-dir",
                "exitCode" to result.exitCode,
                "durationMs" to result.durationMs
            )
        }

        "sync-with-rollback" -> {
            val result = ssh.syncWithRollback(
                SSHSyncRequest(
                    localPath = input.localDir.ifBlank { error("localDir is required for sync-with-rollback") },
                    remotePath = input.remoteDir.ifBlank { error("remoteDir is required for sync-with-rollback") },
                    recursive = true,
                    backupRemote = true,
                    rollbackOnFailure = true,
                    verifyCommands = input.verifyCommands
                )
            )

            mapOf(
                "ok" to true,
                "flow" to "sync-with-rollback",
                "backupPath" to result.backupPath,
                "verifyCount" to result.verify.size,
                "rolledBack" to result.rolledBack
            )
        }

        "template-apply" -> {
            val result = ssh.applyTemplate(
                SSHTemplateRequest(
                    remotePath = input.remotePath,
                    template = input.template.ifBlank { error("template is required for template-apply") },
                    variables = input.variables,
                    backupRemote = true,
                    rollbackOnFailure = true,
                    postWriteCommands = input.postWriteCommands
                )
            )

            mapOf(
                "ok" to true,
                "flow" to "template-apply",
                "remotePath" to result.remotePath,
                "backupPath" to result.backupPath,
                "postWriteCount" to result.postWrite.size,
                "rolledBack" to result.rolledBack
            )
        }

        else -> error("Unsupported mode '${input.mode}'. Use roundtrip-edit, download-dir, upload-dir, sync-with-rollback, template-apply.")
    }
}
