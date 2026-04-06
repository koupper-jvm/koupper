package com.koupper.providers.ssh

data class SSHConnectionConfig(
    val host: String,
    val username: String,
    val port: Int = 22,
    val identityFile: String? = null,
    val password: String? = null,
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

data class SSHRemoteTreeNode(
    val name: String,
    val path: String,
    val type: String,
    val depth: Int,
    val children: List<SSHRemoteTreeNode> = emptyList()
)

data class SSHRemoteTreeResult(
    val rootPath: String,
    val depth: Int,
    val source: String,
    val rendered: String,
    val nodes: List<SSHRemoteTreeNode>
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

    fun tree(rootPath: String = ".", depth: Int = 3, includeHidden: Boolean = true): SSHRemoteTreeResult {
        val safeDepth = depth.coerceIn(1, 10)
        val root = rootPath.trim().ifBlank { "." }
        val quotedRoot = "'${root.replace("'", "'\\''")}'"
        val hiddenFilter = if (includeHidden) {
            ""
        } else {
            " ! -name '.*' ! -path '*/.*'"
        }

        val command = """
            ROOT=$quotedRoot
            if command -v tree >/dev/null 2>&1; then
              echo '__KO_TREEPATHS__'
              tree -n -i -f -F ${if (includeHidden) "-a" else ""} -L $safeDepth --noreport "${'$'}ROOT" | sed '/^\s*$/d'
            else
              echo '__KO_FIND__'
              if find "${'$'}ROOT" -maxdepth 0 -printf '' >/dev/null 2>&1; then
                find "${'$'}ROOT" -maxdepth $safeDepth$hiddenFilter -printf '%y|%p\n' | sort
              else
                find "${'$'}ROOT" -maxdepth $safeDepth$hiddenFilter -print | sort
              fi
            fi
        """.trimIndent()

        val output = exec(command).stdout
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return SSHRemoteTreeResult(root, safeDepth, "empty", "(no output)", emptyList())
        }

        val mode = lines.first()
        val payload = lines.drop(1)

        return when (mode) {
            "__KO_TREEPATHS__" -> {
                val nodes = buildNodesFromPathLines(root, payload)
                SSHRemoteTreeResult(
                    rootPath = root,
                    depth = safeDepth,
                    source = "tree",
                    rendered = renderNodes(root, nodes).ifBlank { "(no output)" },
                    nodes = nodes
                )
            }

            "__KO_FIND__" -> {
                val nodes = buildNodesFromFindPayload(root, payload)
                SSHRemoteTreeResult(
                    rootPath = root,
                    depth = safeDepth,
                    source = "find",
                    rendered = renderNodes(root, nodes).ifBlank { "(no output)" },
                    nodes = nodes
                )
            }

            else -> {
                SSHRemoteTreeResult(
                    rootPath = root,
                    depth = safeDepth,
                    source = "raw",
                    rendered = lines.joinToString("\n"),
                    nodes = emptyList()
                )
            }
        }
    }

    private fun buildNodesFromFindPayload(root: String, payload: List<String>): List<SSHRemoteTreeNode> {
        data class MutableNode(
            val name: String,
            val path: String,
            var type: String,
            val children: MutableMap<String, MutableNode> = linkedMapOf()
        )

        val rootName = if (root == "/") "/" else root.substringAfterLast('/').ifBlank { root }
        val rootNode = MutableNode(rootName, root, "d")

        payload
            .flatMap { it.split("\\n") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
            val (type, path) = if ("|" in line) {
                val idx = line.indexOf('|')
                line.substring(0, idx) to line.substring(idx + 1)
            } else {
                "?" to line
            }

            val rel = when {
                path == root -> ""
                path.startsWith("$root/") -> path.removePrefix("$root/")
                root == "/" && path.startsWith("/") -> path.removePrefix("/")
                else -> path
            }

            val parts = rel.split('/').filter { it.isNotBlank() }
            var current = rootNode
            var currentPath = root

            parts.forEachIndexed { i, part ->
                currentPath = if (currentPath == "/") "/$part" else "$currentPath/$part"
                val isLeaf = i == parts.lastIndex
                val inferredType = when {
                    !isLeaf -> "d"
                    type == "d" -> "d"
                    type == "l" -> "l"
                    else -> "f"
                }

                val existing = current.children[part]
                if (existing == null) {
                    val created = MutableNode(part, currentPath, inferredType)
                    current.children[part] = created
                    current = created
                } else {
                    if (existing.type == "f" && inferredType == "d") existing.type = "d"
                    current = existing
                }
            }
        }

        fun toImmutable(node: MutableNode, depth: Int): SSHRemoteTreeNode {
            val sortedChildren = node.children.values
                .sortedWith(compareBy<MutableNode>({ if (it.type == "d") 0 else 1 }, { it.name.lowercase() }))
                .map { toImmutable(it, depth + 1) }

            return SSHRemoteTreeNode(
                name = node.name,
                path = node.path,
                type = node.type,
                depth = depth,
                children = sortedChildren
            )
        }

        return rootNode.children.values
            .sortedWith(compareBy<MutableNode>({ if (it.type == "d") 0 else 1 }, { it.name.lowercase() }))
            .map { toImmutable(it, 1) }
    }

    private fun buildNodesFromPathLines(root: String, payload: List<String>): List<SSHRemoteTreeNode> {
        val converted = payload.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@mapNotNull null

            val marker = line.lastOrNull()
            val type = when (marker) {
                '/' -> "d"
                '@' -> "l"
                else -> "f"
            }

            val cleanPath = when (marker) {
                '/', '@', '*', '=', '|' -> line.dropLast(1)
                else -> line
            }

            "$type|$cleanPath"
        }

        return buildNodesFromFindPayload(root, converted)
    }

    private fun renderNodes(root: String, nodes: List<SSHRemoteTreeNode>): String {
        val out = mutableListOf<String>()
        out += root

        fun typeSuffix(type: String): String = when (type) {
            "d" -> "/"
            "l" -> "@"
            else -> ""
        }

        fun walk(list: List<SSHRemoteTreeNode>, prefix: String) {
            list.forEachIndexed { i, node ->
                val isLast = i == list.lastIndex
                val branch = if (isLast) "└── " else "├── "
                out += "$prefix$branch${node.name}${typeSuffix(node.type)}"
                val childPrefix = if (isLast) "$prefix    " else "$prefix│   "
                walk(node.children, childPrefix)
            }
        }

        walk(nodes, "")
        return out.joinToString("\n")
    }
}
