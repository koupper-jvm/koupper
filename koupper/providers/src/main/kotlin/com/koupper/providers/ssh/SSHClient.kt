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

data class SSHSyncRequest(
    val localPath: String,
    val remotePath: String,
    val recursive: Boolean = true,
    val backupRemote: Boolean = true,
    val rollbackOnFailure: Boolean = true,
    val verifyCommands: List<String> = emptyList()
)

data class SSHSyncResult(
    val upload: SSHTransferResult,
    val backupPath: String?,
    val verify: List<SSHCommandResult>,
    val rolledBack: Boolean
)

data class SSHTemplateRequest(
    val remotePath: String,
    val template: String,
    val variables: Map<String, String> = emptyMap(),
    val backupRemote: Boolean = true,
    val rollbackOnFailure: Boolean = true,
    val postWriteCommands: List<String> = emptyList()
)

data class SSHTemplateResult(
    val remotePath: String,
    val backupPath: String?,
    val write: SSHTransferResult,
    val postWrite: List<SSHCommandResult>,
    val rolledBack: Boolean
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
    fun syncWithRollback(request: SSHSyncRequest): SSHSyncResult
    fun applyTemplate(request: SSHTemplateRequest): SSHTemplateResult
}
