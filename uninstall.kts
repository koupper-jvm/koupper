import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

fun forceUtf8Output() {
    System.setProperty("file.encoding", "UTF-8")
    runCatching {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8.name()))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8.name()))
    }
}

forceUtf8Output()

val cliArgs: Set<String> = runCatching { args.toSet() }.getOrDefault(emptySet())
val forceArg = cliArgs.contains("--force")
val purgeArg = cliArgs.contains("--purge")

val home = System.getProperty("user.home")
val koupperHome = File(home, ".koupper")

data class StopResult(
    val detected: Int,
    val stoppedGracefully: Int,
    val stoppedForcibly: Int,
    val stillRunning: Int
)

fun isOctopusProcess(handle: ProcessHandle): Boolean {
    val commandLine = buildString {
        append(handle.info().commandLine().orElse(""))
        val args = handle.info().arguments().orElse(emptyArray())
        if (args.isNotEmpty()) {
            append(" ")
            append(args.joinToString(" "))
        }
    }

    if (commandLine.isBlank()) return false

    val normalized = commandLine.lowercase().replace('\\', '/')
    return normalized.contains("/.koupper/libs/octopus.jar")
}

fun runPowerShell(script: String): Pair<Int, String> {
    val process = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return exitCode to output
}

fun findOctopusPidsWindows(): List<Long> {
    val query = "Get-CimInstance Win32_Process | " +
            "Where-Object { (${ '$' }_.Name -eq 'java.exe' -or ${ '$' }_.Name -eq 'javaw.exe') -and ${ '$' }_.CommandLine -like '*\\\\.koupper\\\\libs\\\\octopus.jar*' } | " +
            "ForEach-Object { ${ '$' }_.ProcessId }"
    val (_, output) = runPowerShell(query)
    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { it.toLongOrNull() }
        .distinct()
        .toList()
}

fun stopOctopusProcessesWindows(): StopResult {
    val before = findOctopusPidsWindows()
    if (before.isEmpty()) {
        return StopResult(detected = 0, stoppedGracefully = 0, stoppedForcibly = 0, stillRunning = 0)
    }

    before.forEach { pid ->
        runCatching {
            ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    val after = findOctopusPidsWindows()
    val stillRunning = after.size
    val stopped = (before.size - stillRunning).coerceAtLeast(0)
    return StopResult(detected = before.size, stoppedGracefully = 0, stoppedForcibly = stopped, stillRunning = stillRunning)
}

fun stopOctopusProcesses(): StopResult {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (isWindows) {
        return stopOctopusProcessesWindows()
    }

    val targets = ProcessHandle.allProcesses().filter(::isOctopusProcess).toList()
    if (targets.isEmpty()) {
        return StopResult(detected = 0, stoppedGracefully = 0, stoppedForcibly = 0, stillRunning = 0)
    }

    var gracefulStops = 0
    var forcedStops = 0

    targets.forEach { process ->
        if (!process.isAlive) return@forEach

        process.destroy()
        runCatching { process.onExit().get(3, TimeUnit.SECONDS) }

        if (!process.isAlive) {
            gracefulStops += 1
            return@forEach
        }

        process.destroyForcibly()
        runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
        if (!process.isAlive) {
            forcedStops += 1
        }
    }

    val stillRunning = targets.count { it.isAlive }
    return StopResult(
        detected = targets.size,
        stoppedGracefully = gracefulStops,
        stoppedForcibly = forcedStops,
        stillRunning = stillRunning
    )
}

println("[K] Koupper uninstaller")

if (!koupperHome.exists()) {
    println("[OK] Nothing to uninstall: ${koupperHome.absolutePath} was not found.")
    kotlin.system.exitProcess(0)
}

val force = forceArg || (System.getenv("KOUPPER_UNINSTALL_FORCE")?.equals("true", ignoreCase = true) == true)

if (purgeArg) {
    println("[!] Purge mode enabled: removing full ~/.koupper tree")
}

if (!force) {
    print("Delete ${koupperHome.absolutePath}? [y/N]: ")
    val answer = readlnOrNull()?.trim().orEmpty()
    if (answer.lowercase() !in setOf("y", "yes")) {
        println("[!] Uninstall cancelled.")
        kotlin.system.exitProcess(0)
    }
}

val stopResult = stopOctopusProcesses()
if (stopResult.detected > 0) {
    println("[INFO] Detected running Octopus daemon process(es): ${stopResult.detected}")
    println("[INFO] Stopped gracefully: ${stopResult.stoppedGracefully}, forced: ${stopResult.stoppedForcibly}")
    if (stopResult.stillRunning > 0) {
        println("[WARN] Some Octopus process(es) are still running: ${stopResult.stillRunning}")
    }
}

var deleted = runCatching { koupperHome.deleteRecursively() }.getOrDefault(false)

val isWindowsHost = System.getProperty("os.name").lowercase().contains("win")
if ((!deleted || koupperHome.exists()) && isWindowsHost && (forceArg || purgeArg)) {
    println("[INFO] Retrying uninstall after force-stopping javaw processes...")
    runCatching {
        ProcessBuilder("taskkill", "/IM", "javaw.exe", "/T", "/F")
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }
    Thread.sleep(500)
    deleted = runCatching { koupperHome.deleteRecursively() }.getOrDefault(false)
}

if (!deleted || koupperHome.exists()) {
    println("[ERROR] Could not fully remove ${koupperHome.absolutePath}.")
    println("Tips:")
    println("- Close any running koupper/octopus process and try again.")
    println("- On Windows, ensure no java/javaw process is locking files.")
    kotlin.system.exitProcess(1)
}

println("[OK] Koupper files removed from ${koupperHome.absolutePath}.")
println("[INFO] If needed, remove ~/.koupper/bin from your PATH manually.")
println("[INFO] Tip: reinstall fresh with 'kotlinc -script install.kts -- --force'")
