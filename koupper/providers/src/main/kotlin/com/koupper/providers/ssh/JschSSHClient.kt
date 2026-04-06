package com.koupper.providers.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Properties
import java.util.Vector

class JschSSHClient(
    override val config: SSHConnectionConfig
) : SSHClient {

    override fun exec(command: String, options: SSHExecOptions): SSHCommandResult {
        val remoteCommand = if (options.workingDirectory.isNullOrBlank()) {
            command
        } else {
            "cd ${quoteShell(options.workingDirectory)} && $command"
        }

        val start = System.currentTimeMillis()
        return withSession { session ->
            val channel = (session.openChannel("exec") as ChannelExec)
            channel.setCommand(remoteCommand)
            channel.setInputStream(null)

            val stdout = channel.inputStream
            val stderr = channel.errStream

            channel.connect(config.connectTimeoutSeconds * 1000)

            waitForClosure(channel, options.timeoutSeconds ?: config.commandTimeoutSeconds)

            val result = SSHCommandResult(
                command = remoteCommand,
                exitCode = channel.exitStatus,
                stdout = stdout.bufferedReader().readText(),
                stderr = stderr.bufferedReader().readText(),
                durationMs = System.currentTimeMillis() - start
            )

            channel.disconnect()

            if (options.failOnNonZeroExit && !result.ok) {
                throw IllegalStateException("SSH command failed (${result.exitCode}): ${result.stderr.ifBlank { result.stdout }}")
            }

            result
        }
    }

    override fun upload(localPath: String, remotePath: String, recursive: Boolean): SSHTransferResult {
        val start = System.currentTimeMillis()
        return withSftp { sftp ->
            if (recursive) {
                uploadRecursive(sftp, File(localPath), remotePath)
            } else {
                sftp.put(localPath, remotePath)
            }

            SSHTransferResult(
                source = localPath,
                target = remotePath,
                recursive = recursive,
                command = "sftp put",
                exitCode = 0,
                stderr = "",
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    override fun download(remotePath: String, localPath: String, recursive: Boolean): SSHTransferResult {
        val start = System.currentTimeMillis()
        return withSftp { sftp ->
            if (recursive) {
                downloadRecursive(sftp, remotePath, File(localPath))
            } else {
                sftp.get(remotePath, localPath)
            }

            SSHTransferResult(
                source = remotePath,
                target = localPath,
                recursive = recursive,
                command = "sftp get",
                exitCode = 0,
                stderr = "",
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    override fun exists(remotePath: String): Boolean {
        return withSftp { sftp ->
            try {
                sftp.stat(remotePath)
                true
            } catch (_: SftpException) {
                false
            }
        }
    }

    override fun mkdir(remotePath: String, parents: Boolean): SSHCommandResult {
        if (parents) {
            exec("mkdir -p ${quoteShell(remotePath)}")
        }

        return withSftp { sftp ->
            val start = System.currentTimeMillis()
            sftp.mkdir(remotePath)
            SSHCommandResult("sftp mkdir", 0, "", "", System.currentTimeMillis() - start)
        }
    }

    override fun readText(remotePath: String): String {
        return withSftp { sftp ->
            sftp.get(remotePath).bufferedReader().use { it.readText() }
        }
    }

    override fun writeText(remotePath: String, content: String): SSHTransferResult {
        val start = System.currentTimeMillis()
        return withSftp { sftp ->
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)).use { data ->
                sftp.put(data, remotePath)
            }

            SSHTransferResult(
                source = "memory",
                target = remotePath,
                recursive = false,
                command = "sftp put(stream)",
                exitCode = 0,
                stderr = "",
                durationMs = System.currentTimeMillis() - start
            )
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
        val original = File(localPath).readText()
        val updated = transform(original)
        File(localPath).writeText(updated)

        val upload = upload(localPath, request.remotePath, recursive = false)
        val postUpload = request.postUploadCommands.map { exec(it) }

        return SSHRoundTripResult(
            localPath = localPath,
            download = download,
            upload = upload,
            postUpload = postUpload
        )
    }

    override fun syncWithRollback(request: SSHSyncRequest): SSHSyncResult {
        val backupPath = if (request.backupRemote && exists(request.remotePath)) {
            val b = "${request.remotePath}.bak.${System.currentTimeMillis()}"
            exec("cp -R ${quoteShell(request.remotePath)} ${quoteShell(b)}")
            b
        } else null

        val upload = upload(request.localPath, request.remotePath, request.recursive)

        return try {
            val verify = request.verifyCommands.map { exec(it) }
            SSHSyncResult(upload = upload, backupPath = backupPath, verify = verify, rolledBack = false)
        } catch (t: Throwable) {
            if (request.rollbackOnFailure && !backupPath.isNullOrBlank()) {
                exec("rm -rf ${quoteShell(request.remotePath)}", SSHExecOptions(failOnNonZeroExit = false))
                exec("mv ${quoteShell(backupPath)} ${quoteShell(request.remotePath)}")
            }
            throw t
        }
    }

    override fun applyTemplate(request: SSHTemplateRequest): SSHTemplateResult {
        var rendered = request.template
        request.variables.forEach { (k, v) ->
            rendered = rendered.replace("{{$k}}", v).replace("\${$k}", v)
        }

        val backupPath = if (request.backupRemote && exists(request.remotePath)) {
            val b = "${request.remotePath}.bak.${System.currentTimeMillis()}"
            exec("cp ${quoteShell(request.remotePath)} ${quoteShell(b)}")
            b
        } else null

        val write = writeText(request.remotePath, rendered)

        return try {
            val post = request.postWriteCommands.map { exec(it) }
            SSHTemplateResult(request.remotePath, backupPath, write, post, rolledBack = false)
        } catch (t: Throwable) {
            if (request.rollbackOnFailure && !backupPath.isNullOrBlank()) {
                exec("mv ${quoteShell(backupPath)} ${quoteShell(request.remotePath)}")
            }
            throw t
        }
    }

    private fun <T> withSession(block: (Session) -> T): T {
        val jsch = JSch()
        if (!config.identityFile.isNullOrBlank()) {
            jsch.addIdentity(config.identityFile)
        }

        val session = jsch.getSession(config.username, config.host, config.port)
        if (!config.password.isNullOrBlank()) {
            session.setPassword(config.password)
        }

        val props = Properties()
        props["StrictHostKeyChecking"] = if (config.strictHostKeyChecking) "yes" else "no"
        session.setConfig(props)
        session.connect(config.connectTimeoutSeconds * 1000)

        try {
            return block(session)
        } finally {
            session.disconnect()
        }
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T {
        val jsch = JSch()
        if (!config.identityFile.isNullOrBlank()) {
            jsch.addIdentity(config.identityFile)
        }

        val session = jsch.getSession(config.username, config.host, config.port)
        if (!config.password.isNullOrBlank()) {
            session.setPassword(config.password)
        }

        val props = Properties()
        props["StrictHostKeyChecking"] = if (config.strictHostKeyChecking) "yes" else "no"
        session.setConfig(props)
        session.connect(config.connectTimeoutSeconds * 1000)

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(config.connectTimeoutSeconds * 1000)

        try {
            return block(channel)
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    private fun waitForClosure(channel: ChannelExec, timeoutSeconds: Long) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (!channel.isClosed) {
            if (System.currentTimeMillis() > deadline) {
                channel.disconnect()
                throw IllegalStateException("SSH command timed out after ${timeoutSeconds}s")
            }
            Thread.sleep(50)
        }
    }

    private fun uploadRecursive(sftp: ChannelSftp, local: File, remotePath: String) {
        if (local.isDirectory) {
            ensureRemoteDir(sftp, remotePath)
            local.listFiles()?.forEach { child ->
                uploadRecursive(sftp, child, "$remotePath/${child.name}")
            }
        } else {
            ensureRemoteDir(sftp, File(remotePath).parent?.replace('\\', '/') ?: ".")
            sftp.put(local.absolutePath, remotePath)
        }
    }

    private fun downloadRecursive(sftp: ChannelSftp, remotePath: String, local: File) {
        try {
            val attrs = sftp.stat(remotePath)
            if (attrs.isDir) {
                if (!local.exists()) local.mkdirs()
                @Suppress("UNCHECKED_CAST")
                val entries = sftp.ls(remotePath) as Vector<ChannelSftp.LsEntry>
                entries.filter { it.filename != "." && it.filename != ".." }.forEach { entry ->
                    downloadRecursive(sftp, "$remotePath/${entry.filename}", File(local, entry.filename))
                }
            } else {
                local.parentFile?.mkdirs()
                sftp.get(remotePath, local.absolutePath)
            }
        } catch (e: SftpException) {
            throw IllegalStateException("SFTP download failed for '$remotePath': ${e.message}", e)
        }
    }

    private fun ensureRemoteDir(sftp: ChannelSftp, remoteDir: String) {
        if (remoteDir.isBlank() || remoteDir == ".") return
        val normalized = remoteDir.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        var current = if (normalized.startsWith('/')) "/" else ""

        parts.forEach { part ->
            current = if (current.isEmpty() || current == "/") "$current$part" else "$current/$part"
            try {
                sftp.stat(current)
            } catch (_: SftpException) {
                sftp.mkdir(current)
            }
        }
    }

    private fun quoteShell(value: String): String = "'${value.replace("'", "'\\''")}'"
}
