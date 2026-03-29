package com.koupper.octopus.modules

import com.koupper.os.env
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.downloadFile
import java.io.File
import java.net.URI
import java.net.URL

private sealed interface TemplateProjectSource {
    data class LocalDirectory(val dir: File) : TemplateProjectSource
    data class ZipArchive(val pathOrUrl: String) : TemplateProjectSource
}

private sealed interface ProcessManagerSource {
    data class LocalJar(val file: File) : ProcessManagerSource
    data class RemoteJar(val url: String) : ProcessManagerSource
}

fun prepareTemplateProject(context: String, projectName: String, fileHandler: FileHandler): File {
    val source = resolveTemplateProjectSource(context)
    val targetDir = File(context, projectName)

    if (targetDir.exists()) {
        targetDir.deleteRecursively()
    }

    return when (source) {
        is TemplateProjectSource.LocalDirectory -> {
            copyTemplateDirectory(source.dir, targetDir)
            targetDir
        }

        is TemplateProjectSource.ZipArchive -> {
            fileHandler.unzipFile(source.pathOrUrl, projectName)
        }
    }
}

fun resolveAndCopyProcessManagerJar(context: String, libsDir: File, preferredJarName: String): File {
    libsDir.mkdirs()
    val source = resolveProcessManagerSource(context)

    return when (source) {
        is ProcessManagerSource.LocalJar -> {
            val destination = File(libsDir, preferredJarName)
            source.file.copyTo(destination, overwrite = true)
            destination
        }

        is ProcessManagerSource.RemoteJar -> {
            downloadFile(URL(source.url), File(libsDir, preferredJarName).absolutePath)
        }
    }
}

private fun resolveTemplateProjectSource(context: String): TemplateProjectSource {
    val explicitPath = env("MODEL_BACK_PROJECT_PATH", context, required = false, allowEmpty = true, default = "").trim()
    if (explicitPath.isNotBlank()) {
        return explicitPath.asTemplateSourceOrThrow("MODEL_BACK_PROJECT_PATH")
    }

    val localCandidates = listOf(
        File(context, "model-project"),
        File(System.getProperty("user.dir"), "model-project"),
        File(System.getProperty("user.home"), ".koupper/templates/model-project"),
        File(System.getProperty("user.home"), ".koupper/model-project"),
        File(context, "model-project.zip"),
        File(System.getProperty("user.dir"), "model-project.zip"),
        File(System.getProperty("user.home"), ".koupper/templates/model-project.zip"),
        File(System.getProperty("user.home"), ".koupper/model-project.zip")
    )

    localCandidates.firstOrNull { it.exists() }?.let { candidate ->
        return if (candidate.isDirectory) {
            TemplateProjectSource.LocalDirectory(candidate)
        } else {
            TemplateProjectSource.ZipArchive(candidate.absolutePath)
        }
    }

    val modelUrl = env("MODEL_BACK_PROJECT_URL", context, required = false, allowEmpty = true, default = "").trim()
    if (modelUrl.isNotBlank()) {
        return modelUrl.asTemplateSourceOrThrow("MODEL_BACK_PROJECT_URL")
    }

    throw IllegalStateException(
        "Could not resolve model project source. Set MODEL_BACK_PROJECT_PATH, provide a local model-project directory, or configure MODEL_BACK_PROJECT_URL."
    )
}

private fun resolveProcessManagerSource(context: String): ProcessManagerSource {
    val explicitPath = env("OPTIMIZED_PROCESS_MANAGER_PATH", context, required = false, allowEmpty = true, default = "").trim()
    if (explicitPath.isNotBlank()) {
        val file = explicitPath.asExistingFileOrNull()
            ?: throw IllegalStateException("OPTIMIZED_PROCESS_MANAGER_PATH points to a missing file: $explicitPath")
        return ProcessManagerSource.LocalJar(file)
    }

    val octopusVersion = env("OCTOPUS_VERSION", context, required = false, allowEmpty = true, default = "").trim()
    val localCandidates = mutableListOf<File>()

    if (octopusVersion.isNotBlank()) {
        localCandidates += File(System.getProperty("user.home"), ".koupper/libs/octopus-$octopusVersion.jar")
        localCandidates += File(context, "libs/octopus-$octopusVersion.jar")
    }

    localCandidates += File(System.getProperty("user.home"), ".koupper/libs/octopus.jar")
    localCandidates += File(context, "libs/octopus.jar")

    detectRuntimeJar()?.let { localCandidates += it }

    localCandidates.firstOrNull { it.exists() && it.isFile }?.let {
        return ProcessManagerSource.LocalJar(it)
    }

    val optimizedUrl = env("OPTIMIZED_PROCESS_MANAGER_URL", context, required = false, allowEmpty = true, default = "").trim()
    if (optimizedUrl.isNotBlank()) {
        return ProcessManagerSource.RemoteJar(optimizedUrl)
    }

    throw IllegalStateException(
        "Could not resolve process manager jar. Set OPTIMIZED_PROCESS_MANAGER_PATH or OPTIMIZED_PROCESS_MANAGER_URL."
    )
}

private fun String.asTemplateSourceOrThrow(envName: String): TemplateProjectSource {
    val asFile = this.asExistingFileOrNull()
    if (asFile != null) {
        return if (asFile.isDirectory) {
            TemplateProjectSource.LocalDirectory(asFile)
        } else {
            TemplateProjectSource.ZipArchive(asFile.absolutePath)
        }
    }

    if (startsWith("http://") || startsWith("https://")) {
        return TemplateProjectSource.ZipArchive(this)
    }

    throw IllegalStateException("$envName points to an invalid source: $this")
}

private fun String.asExistingFileOrNull(): File? {
    if (isBlank()) return null

    val fileFromUri = runCatching {
        if (startsWith("file:")) File(URI(this)) else File(this)
    }.getOrNull()

    val target = fileFromUri ?: return null
    return if (target.exists()) target else null
}

private fun detectRuntimeJar(): File? {
    val fromCodeSource = runCatching {
        File(ExecutableJarBuilder::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    return fromCodeSource?.takeIf { it.exists() && it.isFile && it.extension.equals("jar", ignoreCase = true) }
}

private fun copyTemplateDirectory(sourceDir: File, targetDir: File) {
    val ignoredNames = setOf(".git", ".idea", ".gradle", "build", "out", ".kotlin")

    sourceDir.walkTopDown()
        .onEnter { dir ->
            val rel = dir.relativeTo(sourceDir).path.replace("\\", "/")
            if (rel.isBlank()) return@onEnter true
            val parts = rel.split("/")
            val blocked = parts.any { it in ignoredNames } || rel.startsWith("wrapper/dists")
            !blocked
        }
        .forEach { source ->
            val rel = source.relativeTo(sourceDir).path
            val destination = if (rel.isBlank()) targetDir else File(targetDir, rel)

            if (source.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
        }
}
