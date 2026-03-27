import java.io.File
import java.util.Locale

println("🐙 \u001B[38;5;141mBootstrapping Koupper Monorepo Environment...\u001B[0m")
println("🔨 Compiling absolute latest sources via Gradle...")

// 1. Compile the Monorepo sub-modules locally
val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
val gradleCmd = if (isWindows) "gradlew.bat" else "./gradlew"

val cliCompilation = ProcessBuilder(if (isWindows) "cmd" else "bash", if (isWindows) "/c" else "-c", "cd koupper-cli && $gradleCmd build jar -x test")
    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

cliCompilation.waitFor()

val octopusCompilation = ProcessBuilder(if (isWindows) "cmd" else "bash", if (isWindows) "/c" else "-c", "cd koupper && $gradleCmd build jar -x test")
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

val cliJarSource = File("koupper-cli/build/libs").listFiles()?.firstOrNull { it.extension == "jar" && !it.name.contains("javadoc") && !it.name.contains("sources") }
val octopusJarSource = File("koupper/build/libs").listFiles()?.firstOrNull { it.extension == "jar" && !it.name.contains("javadoc") && !it.name.contains("sources") }

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
@echo off
java -jar "$userPath\.koupper\libs\koupper-cli.jar" %*
""".trimIndent()

val bashFile = File(binDirectory, "koupper")
val ps1File = File(binDirectory, "koupper.bat")

bashFile.writeText(bashShim)
bashFile.setExecutable(true)

ps1File.writeText(ps1Shim)

println("⚙️ Generating Octopus Invokers in helpers/...")

val bashInvoker = """
#!/bin/bash
java -jar "$userPath/.koupper/libs/octopus.jar" "${'$'}@"
""".trimIndent()

val batInvoker = """
@echo off
java -jar "$userPath\.koupper\libs\octopus.jar" %*
""".trimIndent()

val bashInvokerFile = File(helpersDirectory, "octopusInvoker.sh")
val batInvokerFile = File(helpersDirectory, "octopusInvoker.bat")

bashInvokerFile.writeText(bashInvoker)
bashInvokerFile.setExecutable(true)
batInvokerFile.writeText(batInvoker)

println("\n✅ \u001B[38;5;155mKoupper Framework successfully installed on your machine!\u001B[0m")
println("\n\u001B[33m[IMPORTANT]\u001B[0m Make sure to add the following directory to your system PATH:")
println("👉 \u001B[36m$binDirectory\u001B[0m")
println("\nThen you can run: \u001B[38;5;229mkoupper run your-script.kts\u001B[0m")
