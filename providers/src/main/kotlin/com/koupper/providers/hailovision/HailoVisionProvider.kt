package com.koupper.providers.hailovision

data class FrameExtractRequest(
    val videoPath: String,
    val outputDir: String,
    val maxFrames: Int = 8,
    val everySeconds: Double? = null,
    val format: String = "jpg"
)

data class FrameExtractResult(
    val ok: Boolean,
    val framePaths: List<String> = emptyList(),
    val error: String? = null
)

data class VideoVisionRequest(
    val videoPath: String,
    val maxFrames: Int = 8,
    val everySeconds: Double? = null,
    val ocr: Boolean = false,
    val modelId: String? = null,
    val workDir: String? = null
)

data class FramesVisionRequest(
    val framePaths: List<String>,
    val ocr: Boolean = false,
    val modelId: String? = null
)

data class DetectedObject(
    val label: String,
    val score: Double? = null,
    val bbox: List<Double>? = null // [x,y,w,h] normalized 0..1 optional
)

data class FrameVisionNote(
    val path: String,
    val timestampSec: Double? = null,
    val objects: List<DetectedObject> = emptyList(),
    val ocrText: List<String> = emptyList(),
    val labels: List<String> = emptyList()
)

data class VideoVisionResult(
    val ok: Boolean,
    val hardwareAvailable: Boolean,
    val scene: String? = null,
    val placeGuess: String? = null,
    val objects: List<String> = emptyList(),
    val ocrHints: List<String> = emptyList(),
    val frameNotes: List<FrameVisionNote> = emptyList(),
    val modelId: String? = null,
    val processingTimeMs: Long? = null,
    val error: String? = null,
    val raw: Map<String, Any?>? = null
)

interface HailoVisionProvider {
    fun extractFrames(request: FrameExtractRequest): FrameExtractResult
    fun analyzeVideo(request: VideoVisionRequest): VideoVisionResult
    fun analyzeFrames(request: FramesVisionRequest): VideoVisionResult
    fun isHardwareAvailable(): Boolean
}
