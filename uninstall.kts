import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

fun forceUtf8Output() {
    System.setProperty("file.encoding", "UTF-8")
    runCatching {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8.name()))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8.name()))
    }
}

forceUtf8Output()

val home = System.getProperty("user.home")
val koupperHome = File(home, ".koupper")

println("[K] Koupper uninstaller")

if (!koupperHome.exists()) {
    println("[OK] Nothing to uninstall: ${koupperHome.absolutePath} was not found.")
    kotlin.system.exitProcess(0)
}

val force = System.getenv("KOUPPER_UNINSTALL_FORCE")?.equals("true", ignoreCase = true) == true

if (!force) {
    print("Delete ${koupperHome.absolutePath}? [y/N]: ")
    val answer = readlnOrNull()?.trim().orEmpty()
    if (answer.lowercase() !in setOf("y", "yes")) {
        println("[!] Uninstall cancelled.")
        kotlin.system.exitProcess(0)
    }
}

val deleted = runCatching { koupperHome.deleteRecursively() }.getOrDefault(false)

if (!deleted || koupperHome.exists()) {
    println("[ERROR] Could not fully remove ${koupperHome.absolutePath}.")
    println("Tips:")
    println("- Close any running koupper/octopus process and try again.")
    println("- On Windows, ensure no java/javaw process is locking files.")
    kotlin.system.exitProcess(1)
}

println("[OK] Koupper files removed from ${koupperHome.absolutePath}.")
println("[INFO] If needed, remove ~/.koupper/bin from your PATH manually.")
