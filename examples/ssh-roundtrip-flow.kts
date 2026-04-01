import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.ssh.SSHClient
import com.koupper.providers.ssh.SSHRoundTripRequest

data class Input(
    val mode: String,
    val remotePath: String,
    val localWorkingDir: String = ".koupper/ssh-work",
    val localFileName: String = "remote-file.txt",
    val appendLine: String = "",
    val postUploadCommands: List<String> = emptyList(),
    val remoteDir: String = "",
    val localDir: String = ""
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

        else -> error("Unsupported mode '${input.mode}'. Use roundtrip-edit, download-dir, upload-dir.")
    }
}
