package com.koupper.providers.hailovision

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class CliHailoVisionProvider : HailoVisionProvider {

    private val mapper = jacksonObjectMapper()

    override fun isHardwareAvailable(): Boolean {
        val devicePath = System.getenv("HAILO_DEVICE") ?: "/dev/hailo0"
        return File(devicePath).exists()
    }

    override fun extractFrames(request: FrameExtractRequest): FrameExtractResult {
        val ffmpegBin = System.getenv("HAILO_FFMPEG_BIN") ?: "ffmpeg"
        val outDir = File(request.outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val fps = if (request.everySeconds != null) (1.0 / request.everySeconds).toString() else "1"
        
        val cmd = listOf(
            ffmpegBin, "-y", "-i", request.videoPath,
            "-vf", "fps=$fps",
            "-vframes", request.maxFrames.toString(),
            File(outDir, "frame_%04d.${request.format}").absolutePath
        )

        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0) {
                val frames = outDir.listFiles { _, name -> name.startsWith("frame_") && name.endsWith(request.format) }
                    ?.map { it.absolutePath }
                    ?.sorted() ?: emptyList()
                FrameExtractResult(ok = true, framePaths = frames)
            } else {
                FrameExtractResult(ok = false, error = "ffmpeg failed or timed out. Exit code: ${process.exitValue()}")
            }
        } catch (e: Exception) {
            FrameExtractResult(ok = false, error = e.message)
        }
    }

    override fun analyzeFrames(request: FramesVisionRequest): VideoVisionResult {
        if (!isHardwareAvailable()) {
            return VideoVisionResult(
                ok = false,
                hardwareAvailable = false,
                error = "Hardware /dev/hailo0 not available on this host."
            )
        }

        val pythonBin = System.getenv("HAILO_PYTHON") ?: "python3"
        val runnerScript = System.getenv("HAILO_RUNNER") ?: "${System.getProperty("user.home")}/.koupper/helpers/hailo_vision_runner.py"

        if (!File(runnerScript).exists()) {
             return VideoVisionResult(
                ok = false,
                hardwareAvailable = true,
                error = "Runner script not found at $runnerScript"
            )
        }

        val startTime = System.currentTimeMillis()
        
        val cmd = mutableListOf(pythonBin, runnerScript)
        if (request.ocr) cmd.add("--ocr")
        if (request.modelId != null) {
            cmd.add("--model")
            cmd.add(request.modelId)
        }
        cmd.addAll(request.framePaths)

        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(120, TimeUnit.SECONDS)
            
            if (completed && process.exitValue() == 0) {
                try {
                    val jsonStr = output.substringAfterLast("JSON_START").substringBeforeLast("JSON_END").trim()
                    val result = if(jsonStr.isNotEmpty()) jsonStr else output
                    val parsed: VideoVisionResult = mapper.readValue(result)
                    parsed.copy(processingTimeMs = System.currentTimeMillis() - startTime, hardwareAvailable = true)
                } catch (e: Exception) {
                    VideoVisionResult(ok = false, hardwareAvailable = true, error = "Failed to parse Python output: ${e.message}", raw = mapOf("output" to output))
                }
            } else {
                VideoVisionResult(ok = false, hardwareAvailable = true, error = "Python runner failed. Output: $output")
            }
        } catch (e: Exception) {
            VideoVisionResult(ok = false, hardwareAvailable = true, error = e.message)
        }
    }

    override fun analyzeVideo(request: VideoVisionRequest): VideoVisionResult {
        val workDir = request.workDir ?: Files.createTempDirectory("hailo_frames").toFile().absolutePath
        
        val extractRes = extractFrames(
            FrameExtractRequest(
                videoPath = request.videoPath,
                outputDir = workDir,
                maxFrames = request.maxFrames,
                everySeconds = request.everySeconds
            )
        )

        if (!extractRes.ok || extractRes.framePaths.isEmpty()) {
            return VideoVisionResult(
                ok = false, 
                hardwareAvailable = isHardwareAvailable(), 
                error = extractRes.error ?: "Failed to extract frames"
            )
        }

        val analyzeRes = analyzeFrames(
            FramesVisionRequest(
                framePaths = extractRes.framePaths,
                ocr = request.ocr,
                modelId = request.modelId
            )
        )
        
        if (request.workDir == null) {
            File(workDir).deleteRecursively()
        }

        return analyzeRes
    }
}
