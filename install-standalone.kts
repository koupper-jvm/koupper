import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

data class Options(
    val force: Boolean = false,
    val doctor: Boolean = false,
    val version: String? = null
)

fun parseOptions(args: Array<String>): Options {
    var force = false
    var doctor = false
    var version: String? = null
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--force" -> force = true
            "--doctor", "--verify" -> doctor = true
            "--version" -> {
                val next = args.getOrNull(i + 1)
                if (next == null || next.startsWith("--")) {
                    fail("Missing value for --version")
                }
                version = next
                i++
            }
            else -> fail("Unknown argument: $arg")
        }
        i++
    }
    return Options(force = force, doctor = doctor, version = version)
}

fun fail(message: String): Nothing {
    System.err.println("[ERROR] $message")
    exitProcess(1)
}

fun forceUtf8Output() {
    System.setProperty("file.encoding", "UTF-8")
    runCatching {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8.name()))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8.name()))
    }
}

fun httpGetText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "koupper-standalone-installer")
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000

    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream.bufferedReader(Charsets.UTF_8).readText()
    if (code !in 200..299) {
        fail("HTTP $code while requesting $url. Response: $text")
    }
    return text
}

fun downloadToFile(url: String, file: File) {
    file.parentFile?.mkdirs()
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.setRequestProperty("User-Agent", "koupper-standalone-installer")
    connection.connectTimeout = 15_000
    connection.readTimeout = 120_000

    val code = connection.responseCode
    if (code !in 200..299) {
        val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        fail("HTTP $code while downloading $url. Response: $text")
    }

    connection.inputStream.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun cleanDirectory(dir: File) {
    if (dir.exists()) dir.deleteRecursively()
    dir.mkdirs()
}

fun unzip(zipFile: File, targetDir: File) {
    cleanDirectory(targetDir)
    zipFile.inputStream().use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                val canonicalTarget = outFile.canonicalFile
                val canonicalBase = targetDir.canonicalFile
                if (!canonicalTarget.path.startsWith(canonicalBase.path)) {
                    fail("Blocked suspicious zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}

fun normalizeSingleRoot(dir: File): File {
    val children = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
    return if (children.size == 1) children.first() else dir
}

fun parseTagName(json: String): String {
    val match = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)
    return match?.groupValues?.get(1) ?: fail("Could not parse release tag from GitHub API response")
}

fun parseAssetUrls(json: String): Map<String, String> {
    return Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
        .findAll(json)
        .map { it.groupValues[1].replace("\\/", "/") }
        .associateBy { it.substringAfterLast('/') }
}

fun parseSha256Sums(file: File): Map<String, String> {
    return file.readLines(Charsets.UTF_8)
        .mapNotNull { line ->
            val cleaned = line.trim()
            if (cleaned.isBlank() || cleaned.startsWith("#")) return@mapNotNull null
            val parts = cleaned.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hash = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            name to hash
        }
        .toMap()
}

fun safeDeleteDirectory(dir: File, title: String) {
    if (!dir.exists()) return
    val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
    if (!ok && dir.exists()) {
        fail("Could not remove $title at ${dir.absolutePath}. Close running koupper/octopus processes and retry.")
    }
}

fun safeCopy(source: File, target: File, title: String) {
    val copied = runCatching { source.copyTo(target, overwrite = true) }
    if (copied.isFailure) {
        fail("Could not replace $title at ${target.absolutePath}. Ensure daemon/CLI is not locking this file and retry.")
    }
}

fun writeShims(binDirectory: File, libsDirectory: File, userHomePath: String) {
    val bashShim = """
#!/bin/bash
set -e

octopus_running=false
if (echo > /dev/tcp/127.0.0.1/9998) >/dev/null 2>&1; then
  octopus_running=true
fi

if [ "${'$'}octopus_running" = false ]; then
  nohup java -jar "$userHomePath/.koupper/libs/octopus.jar" >/dev/null 2>&1 &
  sleep 2
fi

java -Dfile.encoding=UTF-8 -jar "$userHomePath/.koupper/libs/koupper-cli.jar" "${'$'}@"
""".trimIndent()

    val ps1Shim = """
${'$'}OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 > ${'$'}null

${'$'}octopusRunning = ${'$'}false
try {
    ${'$'}client = [System.Net.Sockets.TcpClient]::new()
    ${'$'}client.Connect("localhost", 9998)
    ${'$'}client.Close()
    ${'$'}octopusRunning = ${'$'}true
} catch {
    ${'$'}octopusRunning = ${'$'}false
}

if (-not ${'$'}octopusRunning) {
    Start-Process -FilePath "javaw" -ArgumentList "-jar `"${'$'}env:USERPROFILE\.koupper\libs\octopus.jar`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
}

& java "-Dfile.encoding=UTF-8" -jar "${'$'}env:USERPROFILE\.koupper\libs\koupper-cli.jar" ${'$'}args
""".trimIndent()

    val bashFile = File(binDirectory, "koupper")
    val ps1File = File(binDirectory, "koupper.ps1")

    bashFile.writeText(bashShim)
    bashFile.setExecutable(true)
    ps1File.writeText(ps1Shim)
}

fun runDoctor(
    koupperHome: File,
    libsDirectory: File,
    templatesDirectory: File,
    catalogDirectory: File,
    binDirectory: File,
    helpersDirectory: File,
    logsDirectory: File
): Boolean {
    val checks = listOf(
        "~/.koupper exists" to koupperHome.exists(),
        "Helpers directory (~/.koupper/helpers)" to (helpersDirectory.exists() && helpersDirectory.isDirectory),
        "Logs directory (~/.koupper/logs)" to (logsDirectory.exists() && logsDirectory.isDirectory),
        "CLI jar (~/.koupper/libs/koupper-cli.jar)" to File(libsDirectory, "koupper-cli.jar").exists(),
        "Octopus jar (~/.koupper/libs/octopus.jar)" to File(libsDirectory, "octopus.jar").exists(),
        "Template settings.gradle" to File(templatesDirectory, "model-project/settings.gradle").exists(),
        "Providers catalog (~/.koupper/catalog/providers.json)" to File(catalogDirectory, "providers.json").exists(),
        "Bin shim (~/.koupper/bin/koupper)" to File(binDirectory, "koupper").exists(),
        "PowerShell shim (~/.koupper/bin/koupper.ps1)" to File(binDirectory, "koupper.ps1").exists()
    )
    println("[*] Koupper standalone doctor")
    checks.forEach { (name, ok) -> println("[${if (ok) "OK" else "FAIL"}] $name") }
    return checks.all { it.second }
}

forceUtf8Output()

val options = parseOptions(args)
val userHome = File(System.getProperty("user.home"))
val koupperHome = File(userHome, ".koupper")
val libsDirectory = File(koupperHome, "libs")
val templatesDirectory = File(koupperHome, "templates")
val catalogDirectory = File(koupperHome, "catalog")
val binDirectory = File(koupperHome, "bin")
val helpersDirectory = File(koupperHome, "helpers")
val logsDirectory = File(koupperHome, "logs")
val cacheDirectory = File(koupperHome, "cache/standalone-downloads")

if (options.doctor) {
    val ok = runDoctor(
        koupperHome,
        libsDirectory,
        templatesDirectory,
        catalogDirectory,
        binDirectory,
        helpersDirectory,
        logsDirectory
    )
    if (!ok) exitProcess(1)
    println("[OK] Standalone install looks healthy.")
    exitProcess(0)
}

val ownerRepo = System.getenv("KOUPPER_RELEASE_REPO")?.ifBlank { null } ?: "koupper-jvm/koupper"
val releaseEndpoint = if (!options.version.isNullOrBlank()) {
    "https://api.github.com/repos/$ownerRepo/releases/tags/${options.version}"
} else {
    "https://api.github.com/repos/$ownerRepo/releases/latest"
}

println("[*] Resolving release metadata from $releaseEndpoint")
val releaseJson = httpGetText(releaseEndpoint)
val tag = parseTagName(releaseJson)
val assets = parseAssetUrls(releaseJson)

val required = listOf("koupper-cli.jar", "octopus.jar", "model-project.zip", "providers.json", "SHA256SUMS")
val missing = required.filterNot { assets.containsKey(it) }
if (missing.isNotEmpty()) {
    fail("Release $tag is missing required assets: ${missing.joinToString(", ")}")
}

val releaseCache = File(cacheDirectory, tag)
if (options.force) {
    safeDeleteDirectory(releaseCache, "release cache")
}
releaseCache.mkdirs()

required.forEach { name ->
    val file = File(releaseCache, name)
    if (!file.exists() || file.length() == 0L || options.force) {
        println("[*] Downloading $name")
        downloadToFile(assets.getValue(name), file)
    }
}

val checksums = parseSha256Sums(File(releaseCache, "SHA256SUMS"))
required.filter { it != "SHA256SUMS" }.forEach { name ->
    val expected = checksums[name] ?: fail("Missing checksum for $name in SHA256SUMS")
    val actual = sha256(File(releaseCache, name))
    if (!actual.equals(expected, ignoreCase = true)) {
        fail("Checksum mismatch for $name. Expected $expected, got $actual")
    }
}

if (options.force) {
    safeDeleteDirectory(libsDirectory, "libs directory")
    safeDeleteDirectory(templatesDirectory, "templates directory")
    safeDeleteDirectory(catalogDirectory, "catalog directory")
    safeDeleteDirectory(binDirectory, "bin directory")
}

listOf(libsDirectory, templatesDirectory, catalogDirectory, binDirectory, helpersDirectory, logsDirectory).forEach {
    if (!it.exists()) it.mkdirs()
}

safeCopy(File(releaseCache, "koupper-cli.jar"), File(libsDirectory, "koupper-cli.jar"), "CLI jar")
safeCopy(File(releaseCache, "octopus.jar"), File(libsDirectory, "octopus.jar"), "Octopus jar")
safeCopy(File(releaseCache, "providers.json"), File(catalogDirectory, "providers.json"), "providers catalog")

val templateDir = File(templatesDirectory, "model-project")
unzip(File(releaseCache, "model-project.zip"), templateDir)
val normalized = normalizeSingleRoot(templateDir)
if (normalized.canonicalPath != templateDir.canonicalPath) {
    val tmp = File(templatesDirectory, "model-project__tmp")
    safeDeleteDirectory(tmp, "temporary template directory")
    normalized.copyRecursively(tmp, overwrite = true)
    safeDeleteDirectory(templateDir, "model-project directory")
    tmp.copyRecursively(templateDir, overwrite = true)
    safeDeleteDirectory(tmp, "temporary template directory")
}

if (!File(templateDir, "settings.gradle").exists()) {
    fail("Template install incomplete: settings.gradle missing in ${templateDir.absolutePath}")
}

writeShims(binDirectory, libsDirectory, userHome.absolutePath)

println("[OK] Koupper standalone install completed from release $tag at ${Instant.now()}")
println("[PATH] Add ${binDirectory.absolutePath} to your PATH if needed")
