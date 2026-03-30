package com.koupper.cli.commands

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleCommandAddScriptsTest {

    @Test
    fun `module add-scripts should import script into existing module`() {
        val root = createWorkspace()
        val moduleDir = createModule(root, moduleName = "demo", packageName = "demo.app")
        val srcScript = File(root, "extensions/sample.kts")
        srcScript.parentFile.mkdirs()
        srcScript.writeText("""package %PACKAGE%\n\n@Export\nval sample = 1""")

        val command = ModuleCommand()
        val result = command.execute(
            root.absolutePath,
            "add-scripts",
            "name=\"demo\" --script-inclusive \"extensions/sample.kts\""
        )

        val imported = File(moduleDir, "src/main/kotlin/demo/app/extensions/sample.kts")
        assertTrue(imported.exists(), "Expected imported script at ${imported.absolutePath}")
        assertTrue(imported.readText().contains("package demo.app"), "Expected %PACKAGE% replacement with module package")
        assertTrue(result.contains("Added: 1"), "Expected one added script, got: $result")
    }

    @Test
    fun `module add-scripts should not overwrite existing file by default`() {
        val root = createWorkspace()
        val moduleDir = createModule(root, moduleName = "demo", packageName = "demo.app")
        val imported = File(moduleDir, "src/main/kotlin/demo/app/extensions/sample.kts")
        imported.parentFile.mkdirs()
        imported.writeText("original")

        val srcScript = File(root, "extensions/sample.kts")
        srcScript.parentFile.mkdirs()
        srcScript.writeText("updated")

        val command = ModuleCommand()
        val result = command.execute(
            root.absolutePath,
            "add-scripts",
            "name=\"demo\" --script-inclusive \"extensions/sample.kts\""
        )

        assertEquals("original", imported.readText(), "Existing script should remain untouched without --overwrite")
        assertTrue(result.contains("Skipped (exists): 1"), "Expected one skipped file, got: $result")
    }

    @Test
    fun `module add-scripts should overwrite existing files when overwrite flag is used`() {
        val root = createWorkspace()
        val moduleDir = createModule(root, moduleName = "demo", packageName = "demo.app")
        val imported = File(moduleDir, "src/main/kotlin/demo/app/extensions/sample.kts")
        imported.parentFile.mkdirs()
        imported.writeText("original")

        val srcScript = File(root, "extensions/sample.kts")
        srcScript.parentFile.mkdirs()
        srcScript.writeText("updated")

        val command = ModuleCommand()
        val result = command.execute(
            root.absolutePath,
            "add-scripts",
            "name=\"demo\" --script-inclusive \"extensions/sample.kts\" --overwrite"
        )

        assertEquals("updated", imported.readText(), "Expected existing script to be replaced with --overwrite")
        assertTrue(result.contains("Added: 1"), "Expected one added file, got: $result")
        assertTrue(result.contains("Skipped (exists): 0"), "Expected zero skipped files with --overwrite, got: $result")
    }

    @Test
    fun `module add-scripts wildcard inclusive should preserve nested structure`() {
        val root = createWorkspace()
        val moduleDir = createModule(root, moduleName = "demo", packageName = "demo.app")

        val nested = File(root, "extensions/jobs/nested")
        nested.mkdirs()
        File(nested, "one.kts").writeText("one")
        File(nested.parentFile, "two.kt").writeText("two")

        val command = ModuleCommand()
        val result = command.execute(
            root.absolutePath,
            "add-scripts",
            "name=\"demo\" --script-wildcard-inclusive \"extensions/jobs/*\""
        )

        val one = File(moduleDir, "src/main/kotlin/demo/app/extensions/nested/one.kts")
        val two = File(moduleDir, "src/main/kotlin/demo/app/extensions/two.kt")

        assertTrue(one.exists(), "Expected nested script to be copied preserving structure")
        assertTrue(two.exists(), "Expected sibling script to be copied preserving structure")
        assertTrue(result.contains("Added: 2"), "Expected two imported scripts, got: $result")
    }

    @Test
    fun `module add-scripts wildcard flags should accept shell-expanded file paths`() {
        val root = createWorkspace()
        val moduleDir = createModule(root, moduleName = "demo", packageName = "demo.app")

        val src = File(root, "extensions/hello-world.kts")
        src.parentFile.mkdirs()
        src.writeText("println(\"hello\")")

        val command = ModuleCommand()
        val result = command.execute(
            root.absolutePath,
            "add-scripts",
            "name=\"demo\" --script-wildcard-exclusive \"extensions/hello-world.kts\" --overwrite"
        )

        val copied = File(moduleDir, "src/main/kotlin/demo/app/extensions/hello-world.kts")
        assertTrue(copied.exists(), "Expected file copy for expanded wildcard path")
        assertTrue(result.contains("Added: 1"), "Expected one added file, got: $result")
    }

    @Test
    fun `module add-scripts should accept windows-style slash paths`() {
        val root = createWorkspace()
        val moduleDir = createModule(root, moduleName = "demo", packageName = "demo.app")

        val src = File(root, "extensions\\hello-world.kts")
        src.parentFile.mkdirs()
        src.writeText("println(\"hello\")")

        val command = ModuleCommand()
        val result = command.execute(
            root.absolutePath,
            "add-scripts",
            "name=\"demo\" --script-inclusive \"extensions\\hello-world.kts\""
        )

        val copied = File(moduleDir, "src/main/kotlin/demo/app/extensions/hello-world.kts")
        assertTrue(copied.exists(), "Expected windows-style path to be normalized and copied")
        assertTrue(result.contains("Added: 1"), "Expected one added file, got: $result")
    }

    private fun createWorkspace(): File {
        return Files.createTempDirectory("koupper-module-add-scripts").toFile().also { it.deleteOnExit() }
    }

    private fun createModule(root: File, moduleName: String, packageName: String): File {
        val moduleDir = File(root, moduleName)
        val pkgPath = packageName.replace('.', '/')
        File(moduleDir, "src/main/kotlin/$pkgPath/extensions").mkdirs()
        return moduleDir
    }
}
