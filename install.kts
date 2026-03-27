import java.io.File
import java.util.Locale

println("🐙 \u001B[38;5;141mBootstrapping Koupper Monorepo Environment...\u001B[0m")
println("🔨 Compiling absolute latest sources via Gradle...")

// 1. Compile the Monorepo sub-modules locally
val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
val gradleCmd = if (isWindows) "gradlew.bat" else "./gradlew"

val cliCompilation = ProcessBuilder(if (isWindows) "cmd" else "bash", if (isWindows) "/c" else "-c", "cd koupper-cli && $gradleCmd jar -x test")
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

cliCompilation.waitFor()

val octopusCompilation = ProcessBuilder(if (isWindows) "cmd" else "bash", if (isWindows) "/c" else "-c", "cd koupper && $gradleCmd :octopus:fatJar -x test")
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

octopusCompilation.waitFor()

if (cliCompilation.exitValue() != 0 || octopusCompilation.exitValue() != 0) {
    println("\u001B[31m❌ Compilation failed. Aborting installation.\u001B[0m")
    System.exit(1)
}

// 2. Provision Directory Paths
val userPath = System.getProperty("user.home")
val binDirectory = File("$userPath${File.separator}.koupper${File.separator}bin")
val libsDirectory = File("$userPath${File.separator}.koupper${File.separator}libs")
val logsDirectory = File("$userPath${File.separator}.koupper${File.separator}logs")
val helpersDirectory = File("$userPath${File.separator}.koupper${File.separator}helpers")

arrayOf(binDirectory, libsDirectory, logsDirectory, helpersDirectory).forEach {
    if (!it.exists()) it.mkdirs()
}

// 3. Move freshly compiled JARS
println("📦 Deploying artifacts...")

val cliJarSource = File("koupper-cli/build/libs").listFiles()
    ?.filter { it.extension == "jar" && !it.name.contains("javadoc") && !it.name.contains("sources") }
    ?.maxByOrNull { it.length() }

val octopusJarSource = File("koupper/octopus/build/libs").listFiles()
    ?.filter { it.extension == "jar" && !it.name.contains("javadoc") && !it.name.contains("sources") }
    ?.maxByOrNull { it.length() }

if (cliJarSource == null || octopusJarSource == null) {
    println("\u001B[31m❌ Artifacts not found after compilation. Expected Jars in build/libs.\u001B[0m")
    System.exit(1)
}

val cliTarget = File(libsDirectory, "koupper-cli.jar")
val octopusTarget = File(libsDirectory, "octopus.jar")

cliJarSource!!.copyTo(cliTarget, overwrite = true)
octopusJarSource!!.copyTo(octopusTarget, overwrite = true)

// 4. Generate Bin Shims for Windows and Unix
println("⚙️ Generating CLI shims...")

val bashShim = """
#!/bin/bash
java -jar "$userPath/.koupper/libs/koupper-cli.jar" "${'$'}@"
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
    Start-Process -FilePath "javaw" -ArgumentList "-jar `"${'$'}userPath\.koupper\libs\octopus.jar`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
}

# 2. CLI Execution
# We pipe to Out-Default to force PowerShell's PSReadLine to track the output lines natively.
& java "-Dfile.encoding=UTF-8" -jar "${'$'}userPath\.koupper\libs\koupper-cli.jar" ${'$'}args | Out-Default
""".trimIndent()

val bashFile = File(binDirectory, "koupper")
val ps1File = File(binDirectory, "koupper.ps1")

bashFile.writeText(bashShim)
bashFile.setExecutable(true)

ps1File.writeText(ps1Shim)

println("⚙️ Generating Octopus Invokers in helpers/...")

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

println("\n✅ \u001B[38;5;155mKoupper Framework successfully installed on your machine!\u001B[0m")
println("\n\u001B[33m[IMPORTANT]\u001B[0m Make sure to add the following directory to your system PATH:")
println("👉 \u001B[36m$binDirectory\u001B[0m")
println("\nThen you can run: \u001B[38;5;229mkoupper run your-script.kts\u001B[0m")
