import java.io.File
import java.util.Locale

println("🐙 \u001B[38;5;141mBootstrapping Koupper Monorepo Environment...\u001B[0m")
println("🔨 Compiling absolute latest sources via Gradle...")

// 1. Compile the Monorepo locally
val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
val gradleCmd = if (isWindows) "gradlew.bat" else "./gradlew"

val process = ProcessBuilder(if (isWindows) "cmd" else "bash", if (isWindows) "/c" else "-c", "$gradleCmd build -x test")
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

process.waitFor()
if (process.exitValue() != 0) {
    println("\u001B[31m❌ Compilation failed. Aborting installation.\u001B[0m")
    System.exit(1)
}

// 2. Provision Directory Paths
val userPath = System.getProperty("user.home")
val binDirectory = File("$userPath${File.separator}.koupper${File.separator}bin")
val libsDirectory = File("$userPath${File.separator}.koupper${File.separator}libs")
val logsDirectory = File("$userPath${File.separator}.koupper${File.separator}logs")

arrayOf(binDirectory, libsDirectory, logsDirectory).forEach {
    if (!it.exists()) it.mkdirs()
}

// 3. Move freshly compiled JARS
println("📦 Deploying artifacts...")
val cliJarSource = File("koupper-cli/build/libs/koupper-cli-1.0-SNAPSHOT-all.jar")
val octopusJarSource = File("koupper/build/libs/koupper-1.0-SNAPSHOT-all.jar")

val cliTarget = File(libsDirectory, "koupper-cli.jar")
val octopusTarget = File(libsDirectory, "octopus.jar")

if (cliJarSource.exists()) cliJarSource.copyTo(cliTarget, overwrite = true)
if (octopusJarSource.exists()) octopusJarSource.copyTo(octopusTarget, overwrite = true)

// 4. Generate Bin Shims for Windows and Unix
println("⚙️ Generating CLI shims...")

val bashShim = """
#!/bin/bash
java -jar "$userPath/.koupper/libs/koupper-cli.jar" "${'$'}@"
""".trimIndent()

val ps1Shim = """
@echo off
java -jar "$userPath\.koupper\libs\koupper-cli.jar" %*
""".trimIndent()

val bashFile = File(binDirectory, "koupper")
val ps1File = File(binDirectory, "koupper.bat")

bashFile.writeText(bashShim)
bashFile.setExecutable(true)

ps1File.writeText(ps1Shim)

println("\n✅ \u001B[38;5;155mKoupper Framework successfully installed on your machine!\u001B[0m")
println("\n\u001B[33m[IMPORTANT]\u001B[0m Make sure to add the following directory to your system PATH:")
println("👉 \u001B[36m$binDirectory\u001B[0m")
println("\nThen you can run: \u001B[38;5;229mkoupper run your-script.kts\u001B[0m")
