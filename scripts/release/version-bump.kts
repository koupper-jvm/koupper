import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File

data class Input(
    val projectFile: String = "koupper/build.gradle",
    val targetVersion: String? = null,
    val bump: String = "patch",
    val dryRun: Boolean = true
)

private fun parseVersion(version: String): Triple<Int, Int, Int> {
    val parts = version.split(".")
    if (parts.size != 3) error("unsupported version format: $version")
    return Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}

private fun computeTarget(current: String, bump: String): String {
    val (major, minor, patch) = parseVersion(current)
    return when (bump.lowercase()) {
        "major" -> "${major + 1}.0.0"
        "minor" -> "$major.${minor + 1}.0"
        "patch" -> "$major.$minor.${patch + 1}"
        else -> error("unsupported bump '$bump'. Use major|minor|patch")
    }
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val file = run {
        val direct = File(cwd, input.projectFile)
        if (direct.exists()) {
            direct
        } else {
            var cursor: File? = cwd
            var resolved: File = direct
            var found = false
            repeat(5) {
                if (found) return@repeat
                cursor = cursor?.parentFile
                val candidate = cursor?.let { File(it, input.projectFile) }
                if (candidate != null && candidate.exists()) {
                    resolved = candidate
                    found = true
                }
            }
            resolved
        }
    }
    if (!file.exists()) {
        error("project file not found: ${file.absolutePath}")
    }

    val content = file.readText()
    val regex = Regex("version\\s*=\\s*'([0-9]+\\.[0-9]+\\.[0-9]+)'")
    val match = regex.find(content) ?: error("version assignment not found in ${input.projectFile}")
    val currentVersion = match.groupValues[1]
    val newVersion = input.targetVersion?.takeIf { it.isNotBlank() } ?: computeTarget(currentVersion, input.bump)

    if (!input.dryRun) {
        val updated = content.replaceFirst(regex, "version = '$newVersion'")
        file.writeText(updated)
    }

    mapOf(
        "ok" to true,
        "projectFile" to input.projectFile,
        "currentVersion" to currentVersion,
        "newVersion" to newVersion,
        "dryRun" to input.dryRun
    )
}
