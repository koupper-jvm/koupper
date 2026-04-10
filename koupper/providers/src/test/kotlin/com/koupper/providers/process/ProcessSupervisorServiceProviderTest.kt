package com.koupper.providers.process

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessSupervisorServiceProviderTest : AnnotationSpec() {

    @Test
    fun `should bind process supervisor provider`() {
        ProcessSupervisorServiceProvider().up()
        assertTrue(app.getInstance(ProcessSupervisor::class) is LocalProcessSupervisor)
    }

    @Test
    fun `start status stop should manage detached process`() {
        val fixture = supervisorFixture()
        val supervisor = fixture.supervisor
        val processName = "status-stop-${System.nanoTime()}"

        val started = supervisor.start(
            ProcessStartRequest(
                name = processName,
                executable = fixture.javaExecutable,
                args = fixture.testMainArgs(30000L)
            )
        )

        try {
            val runningStatus = supervisor.status(ProcessStatusRequest(name = processName))
            assertTrue(runningStatus.running)
            assertEquals(started.processId, runningStatus.pid)
            assertNotNull(runningStatus.uptimeMs)

            val stopped = supervisor.stop(ProcessStopRequest(name = processName, force = true))
            assertTrue(stopped.stopped)

            val stoppedStatus = supervisor.status(ProcessStatusRequest(name = processName))
            assertFalse(stoppedStatus.running)
        } finally {
            runCatching { supervisor.stop(ProcessStopRequest(name = processName, force = true)) }
            fixture.cleanup()
        }
    }

    @Test
    fun `start should be idempotent by process name`() {
        val fixture = supervisorFixture()
        val supervisor = fixture.supervisor
        val processName = "idempotent-${System.nanoTime()}"

        val first = supervisor.start(
            ProcessStartRequest(
                name = processName,
                executable = fixture.javaExecutable,
                args = fixture.testMainArgs(30000L)
            )
        )

        try {
            val second = supervisor.start(
                ProcessStartRequest(
                    name = processName,
                    executable = fixture.javaExecutable,
                    args = fixture.testMainArgs(30000L)
                )
            )

            assertEquals(first.processId, second.processId)
            assertTrue(second.alreadyRunning)
        } finally {
            runCatching { supervisor.stop(ProcessStopRequest(name = processName, force = true)) }
            fixture.cleanup()
        }
    }

    @Test
    fun `metadata should persist and be readable in new supervisor instance`() {
        val fixture = supervisorFixture()
        val supervisor = fixture.supervisor
        val processName = "persist-${System.nanoTime()}"

        supervisor.start(
            ProcessStartRequest(
                name = processName,
                executable = fixture.javaExecutable,
                args = fixture.testMainArgs(30000L),
                environment = mapOf("SOME_SECRET" to "value-123")
            )
        )

        try {
            val reloaded = LocalProcessSupervisor(
                storePath = fixture.storeFile.absolutePath,
                logsDirectory = fixture.logsDir.absolutePath
            )

            val items = reloaded.list()
            assertTrue(items.any { it.name == processName })

            val storeContent = fixture.storeFile.readText(Charsets.UTF_8)
            assertTrue(storeContent.contains("\"envKeys\""))
            assertTrue(storeContent.contains("SOME_SECRET"))
            assertFalse(storeContent.contains("value-123"))
        } finally {
            runCatching { supervisor.stop(ProcessStopRequest(name = processName, force = true)) }
            fixture.cleanup()
        }
    }

    @Test
    fun `cleanup should remove orphan records`() {
        val tempDir = Files.createTempDirectory("process-supervisor-orphan").toFile()
        val storeFile = File(tempDir, "processes.json")
        val logsDir = File(tempDir, "logs")
        logsDir.mkdirs()
        storeFile.writeText(
            """
            {
              "version": "1.0",
              "processes": [
                {
                  "name": "orphan-service",
                  "pid": 999999,
                  "command": "dummy",
                  "workingDirectory": ".",
                  "envKeys": [],
                  "startedAt": 1,
                  "logPath": "${logsDir.absolutePath.replace("\\", "\\\\")}\\orphan.log"
                }
              ]
            }
            """.trimIndent()
        )

        val supervisor = LocalProcessSupervisor(
            storePath = storeFile.absolutePath,
            logsDirectory = logsDir.absolutePath
        )

        try {
            val cleanup = supervisor.cleanup()
            assertEquals(1, cleanup.removed)
            assertEquals(0, cleanup.remaining)
            assertTrue(cleanup.removedNames.contains("orphan-service"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private data class SupervisorFixture(
        val rootDir: File,
        val storeFile: File,
        val logsDir: File,
        val javaExecutable: String,
        val classPath: String,
        val supervisor: LocalProcessSupervisor
    ) {
        fun testMainArgs(sleepMs: Long): List<String> {
            return listOf("-cp", classPath, "com.koupper.providers.process.ProcessSupervisorTestMain", sleepMs.toString())
        }

        fun cleanup() {
            rootDir.deleteRecursively()
        }
    }

    private fun supervisorFixture(): SupervisorFixture {
        val rootDir = Files.createTempDirectory("process-supervisor-test").toFile()
        val storeFile = File(rootDir, "processes.json")
        val logsDir = File(rootDir, "logs")
        logsDir.mkdirs()

        val javaHome = System.getProperty("java.home")
        val javaExecutable = File(javaHome, if (isWindows()) "bin/java.exe" else "bin/java").absolutePath
        val classPath = System.getProperty("java.class.path")

        val supervisor = LocalProcessSupervisor(
            storePath = storeFile.absolutePath,
            logsDirectory = logsDir.absolutePath
        )

        return SupervisorFixture(
            rootDir = rootDir,
            storeFile = storeFile,
            logsDir = logsDir,
            javaExecutable = javaExecutable,
            classPath = classPath,
            supervisor = supervisor
        )
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
