package com.koupper.providers.ssh

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

class OpenSSHClient(
    override val config: SSHConnectionConfig
) : SSHClient {

    override fun exec(command: String, options: SSHExecOptions): SSHCommandResult {
        val remoteCommand = if (options.workingDirectory.isNullOrBlank()) {
            command
        } else {
            "cd ${quoteShell(options.workingDirectory)} && $command"
        }

        val args = mutableListOf("ssh")
        args += sshCommonArgs()
        args += targetHost()
        args += remoteCommand

        val result = runProcess(args, options.timeoutSeconds ?: config.commandTimeoutSeconds)
        if (options.failOnNonZeroExit && result.exitCode != 0) {
            throw IllegalStateException("SSH command failed (${result.exitCode}): ${result.stderr.ifBlank { result.stdout }}")
        }
        return result.copy(command = "ssh ${args.drop(1).joinToString(" ")}")
    }

    override fun upload(localPath: String, remotePath: String, recursive: Boolean): SSHTransferResult {
        val args = mutableListOf("scp")
        args += scpCommonArgs(recursive)
        args += localPath
        args += "${targetHost()}:$remotePath"
        return runTransfer(args, localPath, remotePath, recursive)
    }

    override fun download(remotePath: String, localPath: String, recursive: Boolean): SSHTransferResult {
        val args = mutableListOf("scp")
        args += scpCommonArgs(recursive)
        args += "${targetHost()}:$remotePath"
        args += localPath
        return runTransfer(args, remotePath, localPath, recursive)
    }

    override fun exists(remotePath: String): Boolean {
        val res = exec("test -e ${quoteShell(remotePath)}", SSHExecOptions(failOnNonZeroExit = false))
        return res.ok
    }

    override fun mkdir(remotePath: String, parents: Boolean): SSHCommandResult {
        val flag = if (parents) "-p" else ""
        return exec("mkdir $flag ${quoteShell(remotePath)}")
    }

    override fun readText(remotePath: String): String {
        val res = exec("cat ${quoteShell(remotePath)}")
        return res.stdout
    }

    override fun writeText(remotePath: String, content: String): SSHTransferResult {
        val tmp = createTempFile("koupper-ssh-write-", ".tmp").toFile()
        try {
            tmp.writeText(content)
            return upload(tmp.absolutePath, remotePath)
        } finally {
            tmp.delete()
        }
    }

    override fun roundTripEdit(request: SSHRoundTripRequest, transform: (String) -> String): SSHRoundTripResult {
        val localDir = File(request.localWorkingDir)
        if (!localDir.exists()) localDir.mkdirs()

        val localPath = File(localDir, request.localFileName).absolutePath

        if (request.backupRemote) {
            exec("cp ${quoteShell(request.remotePath)} ${quoteShell(request.remotePath)}.bak")
        }

        val download = download(request.remotePath, localPath, recursive = false)
        if (!download.ok) {
            throw IllegalStateException("SSH download failed (${download.exitCode}): ${download.stderr}")
        }

        val original = File(localPath).readText()
        val updated = transform(original)
        File(localPath).writeText(updated)

        val upload = upload(localPath, request.remotePath, recursive = false)
        if (!upload.ok) {
            throw IllegalStateException("SSH upload failed (${upload.exitCode}): ${upload.stderr}")
        }

        val postUpload = request.postUploadCommands.map {
            exec(it)
        }

        return SSHRoundTripResult(
            localPath = localPath,
            download = download,
            upload = upload,
            postUpload = postUpload
        )
    }

    override fun syncWithRollback(request: SSHSyncRequest): SSHSyncResult {
        val backupPath = if (request.backupRemote && exists(request.remotePath)) {
            val b = timestampedBackupPath(request.remotePath)
            exec("cp -R ${quoteShell(request.remotePath)} ${quoteShell(b)}")
            b
        } else {
            null
        }

        val upload = upload(request.localPath, request.remotePath, recursive = request.recursive)
        if (!upload.ok) {
            throw IllegalStateException("SSH sync upload failed (${upload.exitCode}): ${upload.stderr}")
        }

        return try {
            val verify = request.verifyCommands.map { exec(it) }
            SSHSyncResult(
                upload = upload,
                backupPath = backupPath,
                verify = verify,
                rolledBack = false
            )
        } catch (t: Throwable) {
            val rolledBack = if (request.rollbackOnFailure && !backupPath.isNullOrBlank()) {
                rollbackRemotePath(request.remotePath, backupPath)
                true
            } else {
                false
            }
            throw IllegalStateException("SSH sync verification failed (rolledBack=$rolledBack): ${t.message}", t)
        }
    }

    override fun applyTemplate(request: SSHTemplateRequest): SSHTemplateResult {
        val rendered = renderTemplate(request.template, request.variables)

        val backupPath = if (request.backupRemote && exists(request.remotePath)) {
            val b = timestampedBackupPath(request.remotePath)
            exec("cp ${quoteShell(request.remotePath)} ${quoteShell(b)}")
            b
        } else {
            null
        }

        val write = writeText(request.remotePath, rendered)
        if (!write.ok) {
            throw IllegalStateException("SSH template write failed (${write.exitCode}): ${write.stderr}")
        }

        return try {
            val postWrite = request.postWriteCommands.map { exec(it) }
            SSHTemplateResult(
                remotePath = request.remotePath,
                backupPath = backupPath,
                write = write,
                postWrite = postWrite,
                rolledBack = false
            )
        } catch (t: Throwable) {
            val rolledBack = if (request.rollbackOnFailure && !backupPath.isNullOrBlank()) {
                rollbackRemotePath(request.remotePath, backupPath)
                true
            } else {
                false
            }
            throw IllegalStateException("SSH template post-write failed (rolledBack=$rolledBack): ${t.message}", t)
        }
    }

    private fun sshCommonArgs(): List<String> {
        val args = mutableListOf<String>()
        args += listOf("-p", config.port.toString())
        args += listOf("-o", "ConnectTimeout=${config.connectTimeoutSeconds}")
        args += listOf("-o", "StrictHostKeyChecking=${if (config.strictHostKeyChecking) "yes" else "no"}")
        config.identityFile?.takeIf { it.isNotBlank() }?.let { args += listOf("-i", it) }
        return args
    }

    private fun scpCommonArgs(recursive: Boolean): List<String> {
        val args = mutableListOf<String>()
        if (recursive) args += "-r"
        args += listOf("-P", config.port.toString())
        args += listOf("-o", "ConnectTimeout=${config.connectTimeoutSeconds}")
        args += listOf("-o", "StrictHostKeyChecking=${if (config.strictHostKeyChecking) "yes" else "no"}")
        config.identityFile?.takeIf { it.isNotBlank() }?.let { args += listOf("-i", it) }
        return args
    }

    private fun targetHost(): String = "${config.username}@${config.host}"

    private fun timestampedBackupPath(remotePath: String): String = "$remotePath.bak.${System.currentTimeMillis()}"

    private fun rollbackRemotePath(remotePath: String, backupPath: String) {
        exec("rm -rf ${quoteShell(remotePath)}", SSHExecOptions(failOnNonZeroExit = false))
        exec("mv ${quoteShell(backupPath)} ${quoteShell(remotePath)}")
    }

    private fun renderTemplate(template: String, variables: Map<String, String>): String {
        var output = template
        variables.forEach { (key, value) ->
            output = output
                .replace("{{$key}}", value)
                .replace("\${$key}", value)
        }
        return output
    }

    private fun quoteShell(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun runTransfer(args: List<String>, source: String, target: String, recursive: Boolean): SSHTransferResult {
        val start = System.currentTimeMillis()
        val pb = ProcessBuilder(args)
        val proc = pb.start()
        val done = proc.waitFor(config.commandTimeoutSeconds, TimeUnit.SECONDS)
        if (!done) {
            proc.destroyForcibly()
            throw IllegalStateException("SCP command timed out after ${config.commandTimeoutSeconds}s")
        }

        val stderr = proc.errorStream.bufferedReader().readText()
        return SSHTransferResult(
            source = source,
            target = target,
            recursive = recursive,
            command = args.joinToString(" "),
            exitCode = proc.exitValue(),
            stderr = stderr,
            durationMs = System.currentTimeMillis() - start
        )
    }

    private fun runProcess(args: List<String>, timeoutSeconds: Long): SSHCommandResult {
        val start = System.currentTimeMillis()
        val pb = ProcessBuilder(args)
        val proc = pb.start()
        val done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!done) {
            proc.destroyForcibly()
            throw IllegalStateException("SSH command timed out after ${timeoutSeconds}s")
        }

        return SSHCommandResult(
            command = args.joinToString(" "),
            exitCode = proc.exitValue(),
            stdout = proc.inputStream.bufferedReader().readText(),
            stderr = proc.errorStream.bufferedReader().readText(),
            durationMs = System.currentTimeMillis() - start
        )
    }
}
