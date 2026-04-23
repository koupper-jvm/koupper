import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

fun shouldUseEmoji(): Boolean {
    val noEmoji = System.getenv("KOUPPER_NO_EMOJI")?.equals("true", ignoreCase = true) == true
    val asciiOnly = System.getenv("KOUPPER_ASCII")?.equals("true", ignoreCase = true) == true
    val dumbTerm = System.getenv("TERM")?.equals("dumb", ignoreCase = true) == true
    val stdoutEncoding = System.getProperty("sun.stdout.encoding") ?: System.getProperty("file.encoding")
    val utf8 = stdoutEncoding?.contains("UTF-8", ignoreCase = true) == true

    return !noEmoji && !asciiOnly && !dumbTerm && utf8
}

val EMOJI = shouldUseEmoji()

fun icon(emoji: String, fallback: String): String = if (EMOJI) emoji else fallback

fun forceUtf8Output() {
    System.setProperty("file.encoding", "UTF-8")
    runCatching {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8.name()))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8.name()))
    }
}

forceUtf8Output()

val cliArgs: Set<String> = runCatching { args.toSet() }.getOrDefault(emptySet())
val forceReinstall = cliArgs.contains("--force")
val doctorOnly = cliArgs.contains("--doctor") || cliArgs.contains("--verify")

fun cleanDirectory(dir: File) {
    if (dir.exists()) {
        dir.deleteRecursively()
    }
    dir.mkdirs()
}

fun resolveRepoRootFromExtracted(targetDir: File): File {
    val children = targetDir.listFiles()?.filter { it.isDirectory }.orEmpty()
    return if (children.size == 1) children.first() else targetDir
}

fun downloadAndExtractRepoZip(repo: String, branch: String, targetDir: File): File {
    val zipUrl = "https://codeload.github.com/$repo/zip/refs/heads/$branch"
    cleanDirectory(targetDir)

    URL(zipUrl).openStream().use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val rawName = entry.name
                val normalized = rawName.substringAfter('/', "")

                if (normalized.isNotBlank()) {
                    val outFile = File(targetDir, normalized)
                    val canonicalTarget = outFile.canonicalFile
                    val canonicalBase = targetDir.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalBase.path)) {
                        throw IllegalStateException("Blocked suspicious zip entry: $rawName")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zip.copyTo(output) }
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    return resolveRepoRootFromExtracted(targetDir)
}

fun ensureCachedRepo(cacheRoot: File, cacheName: String, repo: String, branch: String): File {
    val cacheDir = File(cacheRoot, cacheName)
    val marker = File(cacheDir, ".source")

    if (!cacheRoot.exists()) cacheRoot.mkdirs()

    val shouldRefresh = !cacheDir.exists() || !File(cacheDir, "gradlew").exists() || !marker.exists()
    if (shouldRefresh) {
        println("${icon("📥", "[*] ")}Fetching $repo ($branch) from public codeload archive...")
        val extracted = downloadAndExtractRepoZip(repo, branch, cacheDir)

        if (extracted.canonicalPath != cacheDir.canonicalPath) {
            val tmp = File(cacheRoot, "${cacheName}__tmp_move")
            cleanDirectory(tmp)
            extracted.copyRecursively(tmp, overwrite = true)
            cleanDirectory(cacheDir)
            tmp.copyRecursively(cacheDir, overwrite = true)
            tmp.deleteRecursively()
        }

        marker.writeText("$repo@$branch")
    }

    return cacheDir
}

fun ensureCliRepoInCache(): File {
    val home = System.getProperty("user.home")
    val cacheRoot = File(home, ".koupper${File.separator}cache")
    return ensureCachedRepo(cacheRoot, "koupper-cli", "koupper-jvm/koupper-cli", "develop")
}

fun resolveCliProjectDir(): File {
    val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
    val wrapper = if (isWindows) "gradlew.bat" else "gradlew"

    val local = File("koupper-cli")
    if (local.exists() && local.isDirectory && File(local, wrapper).exists()) {
        return local
    }

    val sibling = File("..${File.separator}koupper-cli")
    if (sibling.exists() && sibling.isDirectory && File(sibling, wrapper).exists()) {
        return sibling
    }

    val cached = ensureCliRepoInCache()
    if (cached.exists() && cached.isDirectory && File(cached, wrapper).exists()) {
        return cached
    }

    failInstall("koupper-cli project not found. Expected ./koupper-cli, ../koupper-cli, or cached clone at ~/.koupper/cache/koupper-cli.")
}

val cliProjectDir = resolveCliProjectDir()

fun resolveTemplateSourceDir(): File? {
    val fromEnv = System.getenv("MODEL_BACK_PROJECT_PATH")?.takeIf { it.isNotBlank() }?.let { File(it) }
    if (fromEnv != null && fromEnv.exists() && fromEnv.isDirectory) return fromEnv

    val local = File("templates${File.separator}model-project")
    if (local.exists() && local.isDirectory) return local

    val sibling = File("..${File.separator}templates${File.separator}model-project")
    if (sibling.exists() && sibling.isDirectory) return sibling

    val siblingWorkspace = File("..${File.separator}koupper-workspace${File.separator}templates${File.separator}model-project")
    if (siblingWorkspace.exists() && siblingWorkspace.isDirectory) return siblingWorkspace

    val siblingInfraLegacy = File("..${File.separator}koupper-infrastructure${File.separator}templates${File.separator}model-project")
    if (siblingInfraLegacy.exists() && siblingInfraLegacy.isDirectory) return siblingInfraLegacy

    val home = System.getProperty("user.home")
    val cacheRoot = File(home, ".koupper${File.separator}cache")
    val workspaceCacheDir = runCatching {
        ensureCachedRepo(cacheRoot, "koupper-workspace", "koupper-jvm/koupper-workspace", "develop")
    }.getOrNull()

    if (workspaceCacheDir != null) {
        val cachedTemplate = File(workspaceCacheDir, "templates${File.separator}model-project")
        if (cachedTemplate.exists() && cachedTemplate.isDirectory) return cachedTemplate
    }

    val infraCacheDir = File(cacheRoot, "koupper-infrastructure")
    if (infraCacheDir.exists() && infraCacheDir.isDirectory) {
        val cachedTemplate = File(infraCacheDir, "templates${File.separator}model-project")
        if (cachedTemplate.exists() && cachedTemplate.isDirectory) return cachedTemplate
    }

    return null
}

val templateSourceDir = resolveTemplateSourceDir()

val userPath = System.getProperty("user.home")
val koupperHome = File("$userPath${File.separator}.koupper")
val binDirectory = File(koupperHome, "bin")
val libsDirectory = File(koupperHome, "libs")
val logsDirectory = File(koupperHome, "logs")
val helpersDirectory = File(koupperHome, "helpers")
val catalogDirectory = File(koupperHome, "catalog")
val templatesDirectory = File(koupperHome, "templates")
val modelTemplateDirectory = File(templatesDirectory, "model-project")

fun check(name: String, ok: Boolean): String {
    val status = if (ok) "OK" else "FAIL"
    return "[$status] $name"
}

fun failInstall(message: String): Nothing {
    println("\u001B[31m${icon("❌", "[ERROR] ")}$message\u001B[0m")
    exitProcess(1)
}

fun safeDeleteDirectory(dir: File, title: String) {
    if (!dir.exists()) return
    val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
    if (!ok && dir.exists()) {
        failInstall("Could not remove $title at ${dir.absolutePath}. Close running koupper/octopus processes and try again.")
    }
}

fun safeCopy(source: File, target: File, title: String) {
    val copied = runCatching {
        source.copyTo(target, overwrite = true)
    }

    if (copied.isFailure) {
        failInstall("Could not replace $title at ${target.absolutePath}. Ensure daemon/CLI is not locking this file and retry with install -- --force.")
    }
}

fun runDoctorAndExit() {
    println("${icon("🩺", "[*] ")}Koupper installation doctor")

    val checks = listOf(
        check("~/.koupper exists", koupperHome.exists()),
        check("CLI jar (~/.koupper/libs/koupper-cli.jar)", File(libsDirectory, "koupper-cli.jar").exists()),
        check("Octopus jar (~/.koupper/libs/octopus.jar)", File(libsDirectory, "octopus.jar").exists()),
        check("Template directory (~/.koupper/templates/model-project)", modelTemplateDirectory.exists()),
        check("Template settings.gradle", File(modelTemplateDirectory, "settings.gradle").exists()),
        check("Providers catalog (~/.koupper/catalog/providers.json)", File(catalogDirectory, "providers.json").exists()),
        check("Bin shim (~/.koupper/bin/koupper)", File(binDirectory, "koupper").exists()),
        check("PowerShell shim (~/.koupper/bin/koupper.ps1)", File(binDirectory, "koupper.ps1").exists())
    )

    checks.forEach { println(it) }

    val hasFail = checks.any { it.startsWith("[FAIL]") }
    if (hasFail) {
        println("\n${icon("⚠️", "[!] ")}Some checks failed. Run: kotlinc -script install.kts -- --force")
        kotlin.system.exitProcess(1)
    }

    println("\n${icon("✅", "[OK] ")}Installation looks healthy.")
    kotlin.system.exitProcess(0)
}

if (doctorOnly) {
    runDoctorAndExit()
}

println("${icon("🐙", "[K] ")}\u001B[38;5;141mBootstrapping Koupper Monorepo Environment...\u001B[0m")
println("${icon("🔨", "[*] ")}Compiling absolute latest sources via Gradle...")

// 1. Compile the Monorepo sub-modules locally
val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
val gradleCmd = if (isWindows) "gradlew.bat" else "./gradlew"

if (isWindows) {
    runCatching {
        ProcessBuilder("cmd", "/c", "chcp 65001 > nul")
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
    }
}

val cliCompilation = ProcessBuilder(
    if (isWindows) "cmd" else "bash",
    if (isWindows) "/c" else "-c",
    if (isWindows) {
        "cd /d \"${cliProjectDir.absolutePath}\" && $gradleCmd jar -x test"
    } else {
        "cd \"${cliProjectDir.absolutePath}\" && $gradleCmd jar -x test"
    }
)
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .apply {
        environment()["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"
    }
    .start()

cliCompilation.waitFor()

val octopusCompilation = ProcessBuilder(if (isWindows) "cmd" else "bash", if (isWindows) "/c" else "-c", "$gradleCmd :octopus:fatJar -x test")
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .apply {
        environment()["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"
    }
    .start()

octopusCompilation.waitFor()

if (cliCompilation.exitValue() != 0 || octopusCompilation.exitValue() != 0) {
    println("\u001B[31m❌ Compilation failed. Aborting installation.\u001B[0m")
    System.exit(1)
}

// 2. Provision Directory Paths

if (forceReinstall) {
    println("${icon("🧹", "[*] ")}Force mode enabled: cleaning previous installation artifacts...")
    safeDeleteDirectory(binDirectory, "bin directory")
    safeDeleteDirectory(libsDirectory, "libs directory")
    safeDeleteDirectory(helpersDirectory, "helpers directory")
    safeDeleteDirectory(catalogDirectory, "catalog directory")
    safeDeleteDirectory(templatesDirectory, "templates directory")
}

arrayOf(binDirectory, libsDirectory, logsDirectory, helpersDirectory, catalogDirectory, templatesDirectory).forEach {
    if (!it.exists()) it.mkdirs()
}

// 3. Move freshly compiled JARS
println("${icon("📦", "[*] ")}Deploying artifacts...")

val cliJarSource = File(cliProjectDir, "build/libs").listFiles()
    ?.filter { it.extension == "jar" && !it.name.contains("javadoc") && !it.name.contains("sources") }
    ?.maxByOrNull { it.length() }

val octopusJarSource = File("octopus/build/libs").listFiles()
    ?.filter { it.extension == "jar" && !it.name.contains("javadoc") && !it.name.contains("sources") }
    ?.maxByOrNull { it.length() }

if (cliJarSource == null || octopusJarSource == null) {
    println("\u001B[31m❌ Artifacts not found after compilation. Expected Jars in build/libs.\u001B[0m")
    System.exit(1)
}

val cliTarget = File(libsDirectory, "koupper-cli.jar")
val octopusTarget = File(libsDirectory, "octopus.jar")

safeCopy(cliJarSource!!, cliTarget, "CLI jar")
safeCopy(octopusJarSource!!, octopusTarget, "Octopus jar")

// 3.1 Provision local model template (local-first scaffolding)
println("${icon("🧩", "[*] ")}Provisioning local module template...")

val templateSource = templateSourceDir
val templateTarget = modelTemplateDirectory

if (templateSource != null && templateSource.exists() && templateSource.isDirectory) {
    if (templateTarget.exists()) {
        templateTarget.deleteRecursively()
    }
    templateSource.copyRecursively(templateTarget, overwrite = true)
    println("${icon("✅", "[OK] ")}Template installed at ${templateTarget.absolutePath}")
} else {
    failInstall("Template source not found. Set MODEL_BACK_PROJECT_PATH or ensure koupper-workspace templates are available.")
}

// 3.2 Provision providers catalog for CLI discovery
println("${icon("📚", "[*] ")}Provisioning providers catalog...")

val providerCatalogSource = File("providers${File.separator}src${File.separator}main${File.separator}resources${File.separator}providers-catalog.json")
val providerCatalogTarget = File(catalogDirectory, "providers.json")

if (providerCatalogSource.exists() && providerCatalogSource.isFile) {
    safeCopy(providerCatalogSource, providerCatalogTarget, "providers catalog")
    println("${icon("✅", "[OK] ")}Providers catalog installed at ${providerCatalogTarget.absolutePath}")
} else {
    println("${icon("⚠️", "[!] ")}Providers catalog source not found at ${providerCatalogSource.absolutePath}. Skipping catalog provisioning.")
}

// 4. Generate Bin Shims for Windows and Unix
println("${icon("⚙️", "[*] ")}Generating CLI shims...")

val bashShim = """
#!/bin/bash
set -e

octopus_running=false
if (echo > /dev/tcp/127.0.0.1/9998) >/dev/null 2>&1; then
  octopus_running=true
fi

if [ "${'$'}octopus_running" = false ]; then
  echo "🐙 Octopus Engine is offline. Booting background daemon..."
  nohup java -jar "$userPath/.koupper/libs/octopus.jar" >/dev/null 2>&1 &
  sleep 2
fi

java -Dfile.encoding=UTF-8 -jar "$userPath/.koupper/libs/koupper-cli.jar" "${'$'}@"
""".trimIndent()

val ps1Shim = """
# Forced UTF-8 encoding for PowerShell streams
${'$'}OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Force the native Windows Console code page to UTF-8
chcp 65001 > ${'$'}null

# 1. Daemon check - DO NOT USE Test-NetConnection
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
    Write-Host "🐙 Octopus Engine is offline. Booting background daemon..." -ForegroundColor Magenta
    Start-Process -FilePath "javaw" -ArgumentList "-jar `"${'$'}env:USERPROFILE\.koupper\libs\octopus.jar`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
}

# 2. CLI Execution
# Keep direct stdout/stderr to preserve interactive prompt behavior (TerminalIO.prompt).
& java "-Dfile.encoding=UTF-8" -jar "${'$'}env:USERPROFILE\.koupper\libs\koupper-cli.jar" ${'$'}args
""".trimIndent()

val bashFile = File(binDirectory, "koupper")
val ps1File = File(binDirectory, "koupper.ps1")

bashFile.writeText(bashShim)
bashFile.setExecutable(true)

ps1File.writeText(ps1Shim)

println("${icon("⚙️", "[*] ")}Generating Octopus Invokers in helpers/...")

val bashInvoker = """
#!/bin/bash
java -jar "$userPath/.koupper/libs/octopus.jar" "${'$'}@"
""".trimIndent()

val ps1Invoker = """
java -jar "$userPath\.koupper\libs\octopus.jar" ${'$'}args
""".trimIndent()

val bashInvokerFile = File(helpersDirectory, "octopusInvoker.sh")
val ps1InvokerFile = File(helpersDirectory, "octopusInvoker.ps1")

bashInvokerFile.writeText(bashInvoker)
bashInvokerFile.setExecutable(true)
ps1InvokerFile.writeText(ps1Invoker)

println("\n${icon("✅", "[OK] ")}\u001B[38;5;155mKoupper Framework successfully installed on your machine!\u001B[0m")
println("\n\u001B[33m[IMPORTANT]\u001B[0m Make sure to add the following directory to your system PATH:")
println("${icon("👉", "-> ")}\u001B[36m$binDirectory\u001B[0m")
println("\nThen you can run: \u001B[38;5;229mkoupper run your-script.kts\u001B[0m")
