package com.koupper.orchestrator

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.orchestrator.config.JobConfiguration
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FileJobDriverConcurrencyTest : AnnotationSpec() {

    private lateinit var tmpDir: File
    private val queue = "test-queue"

    private fun config() = JobConfiguration(id = "test-cfg", driver = "file", queue = queue)

    @Before
    fun setup() {
        tmpDir = Files.createTempDirectory("koupper-job-driver-test").toFile()
        File(tmpDir, "jobs/$queue").mkdirs()
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    private fun writeJob(id: String): File {
        val task = KouTask(
            id = id,
            fileName = "TestJob",
            functionName = "run",
            scriptPath = "test/script.kt",
            sourceType = "script"
        )
        val file = File(tmpDir, "jobs/$queue/$id.json")
        file.writeText(JobSerializer.serialize(task))
        return file
    }

    // -------------------------------------------------------------------------
    // Invariant: renameTo is the atomic primitive we rely on
    // -------------------------------------------------------------------------

    @Test
    fun `renameTo on a missing source returns false — the claiming invariant`() {
        val jobFile = writeJob("job-atomic-invariant")
        val processingFile = File(jobFile.parent, "${jobFile.name}.processing")

        assertTrue(jobFile.renameTo(processingFile), "First rename must succeed")

        // Source is gone — second rename must fail
        assertFalse(File(jobFile.path).renameTo(processingFile), "Second rename on missing source must return false")
        assertTrue(processingFile.exists())
    }

    // -------------------------------------------------------------------------
    // forEachPending — claiming behaviour
    // -------------------------------------------------------------------------

    @Test
    fun `forEachPending renames json to processing before returning Ok`() {
        writeJob("job-claim-001")

        val results = FileJobDriver.forEachPending(
            context = tmpDir.absolutePath,
            config = config(),
            jobId = null
        )

        val ok = results.filterIsInstance<JobResult.Ok>().single()
        val jsonFile = File(tmpDir, "jobs/$queue/${ok.task.id}.json")
        val processingFile = File(tmpDir, "jobs/$queue/${ok.task.id}.json.processing")

        assertFalse(jsonFile.exists(), ".json must be gone after claim")
        assertTrue(processingFile.exists(), ".json.processing must exist while in-flight")
        assertNotNull(ok.ackFn, "ackFn must be set")
        assertNotNull(ok.releaseFn, "releaseFn must be set")
    }

    @Test
    fun `forEachPending skips files already renamed to processing by another worker`() {
        val jobFile = writeJob("job-skip-002")
        // Simulate another worker claiming the file first
        val processingFile = File(jobFile.parent, "${jobFile.name}.processing")
        jobFile.renameTo(processingFile)

        val results = FileJobDriver.forEachPending(
            context = tmpDir.absolutePath,
            config = config(),
            jobId = null
        )

        val okCount = results.filterIsInstance<JobResult.Ok>().size
        assertEquals(0, okCount, "No job must be returned when the file is already processing")
    }

    // -------------------------------------------------------------------------
    // ackFn / releaseFn lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun `ackFn deletes the processing file`() {
        writeJob("job-ack-003")

        val results = FileJobDriver.forEachPending(
            context = tmpDir.absolutePath,
            config = config(),
            jobId = null
        )

        val ok = results.filterIsInstance<JobResult.Ok>().single()
        val processingFile = File(tmpDir, "jobs/$queue/${ok.task.id}.json.processing")
        assertTrue(processingFile.exists())

        ok.ackFn!!.invoke()

        assertFalse(processingFile.exists(), "Processing file must be deleted after ackFn")
        assertFalse(File(tmpDir, "jobs/$queue/${ok.task.id}.json").exists())
    }

    @Test
    fun `releaseFn moves processing file to failed dir with json extension`() {
        writeJob("job-release-004")

        val results = FileJobDriver.forEachPending(
            context = tmpDir.absolutePath,
            config = config(),
            jobId = null
        )

        val ok = results.filterIsInstance<JobResult.Ok>().single()
        ok.releaseFn!!.invoke()

        val failedFile = File(tmpDir, "jobs/$queue/.failed/${ok.task.id}.json")
        assertTrue(failedFile.exists(), "Failed job must be stored as .json in .failed dir")
        assertFalse(File(tmpDir, "jobs/$queue/${ok.task.id}.json.processing").exists())
        assertFalse(File(tmpDir, "jobs/$queue/${ok.task.id}.json").exists())
    }

    @Test
    fun `malformed json file is moved to failed immediately without returning Ok`() {
        val badFile = File(tmpDir, "jobs/$queue/job-bad-005.json")
        badFile.writeText("{ this is not valid json !!!")

        val results = FileJobDriver.forEachPending(
            context = tmpDir.absolutePath,
            config = config(),
            jobId = null
        )

        val okCount = results.filterIsInstance<JobResult.Ok>().size
        assertEquals(0, okCount, "Malformed job must not produce an Ok result")

        val failedFile = File(tmpDir, "jobs/$queue/.failed/job-bad-005.json")
        assertTrue(failedFile.exists(), "Malformed job must land in .failed")
        assertFalse(File(tmpDir, "jobs/$queue/job-bad-005.json.processing").exists())
    }

    // -------------------------------------------------------------------------
    // Concurrency: no double-processing under parallel workers
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent workers claim each job exactly once`() {
        val jobCount = 20
        repeat(jobCount) { i -> writeJob("job-concurrent-%03d".format(i)) }

        val claimedIds = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(4)

        repeat(4) {
            executor.submit {
                val results = FileJobDriver.forEachPending(
                    context = tmpDir.absolutePath,
                    config = config(),
                    jobId = null
                )
                results.filterIsInstance<JobResult.Ok>().forEach { ok ->
                    claimedIds.add(ok.task.id)
                    ok.ackFn?.invoke()
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        assertEquals(jobCount, claimedIds.size, "Total claimed must equal total dispatched")
        assertEquals(jobCount, claimedIds.distinct().size, "Each job must be claimed exactly once")
    }
}
