package com.koupper.providers.ssh

data class SSHConnectionConfig(
    val host: String,
    val username: String,
    val port: Int = 22,
    val identityFile: String? = null,
    val strictHostKeyChecking: Boolean = false,
    val connectTimeoutSeconds: Int = 20,
    val commandTimeoutSeconds: Long = 120
)

data class SSHExecOptions(
    val timeoutSeconds: Long? = null,
    val workingDirectory: String? = null,
    val failOnNonZeroExit: Boolean = true
)

data class SSHCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
) {
    val ok: Boolean get() = exitCode == 0
}

data class SSHTransferResult(
    val source: String,
    val target: String,
    val recursive: Boolean,
    val command: String,
    val exitCode: Int,
    val stderr: String,
    val durationMs: Long
) {
    val ok: Boolean get() = exitCode == 0
}

data class SSHRoundTripRequest(
    val remotePath: String,
    val localWorkingDir: String,
    val localFileName: String,
    val backupRemote: Boolean = true,
    val postUploadCommands: List<String> = emptyList()
)

data class SSHRoundTripResult(
    val localPath: String,
    val download: SSHTransferResult,
    val upload: SSHTransferResult,
    val postUpload: List<SSHCommandResult>
)

interface SSHClient {
    val config: SSHConnectionConfig

    fun exec(command: String, options: SSHExecOptions = SSHExecOptions()): SSHCommandResult
    fun upload(localPath: String, remotePath: String, recursive: Boolean = false): SSHTransferResult
    fun download(remotePath: String, localPath: String, recursive: Boolean = false): SSHTransferResult
    fun exists(remotePath: String): Boolean
    fun mkdir(remotePath: String, parents: Boolean = true): SSHCommandResult
    fun readText(remotePath: String): String
    fun writeText(remotePath: String, content: String): SSHTransferResult
    fun roundTripEdit(request: SSHRoundTripRequest, transform: (String) -> String): SSHRoundTripResult
}
