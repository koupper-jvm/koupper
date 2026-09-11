import com.koupper.providers.hailovision.*

// Obtain the provider from Koupper's DI container
val hailo = app.getInstance(HailoVisionProvider::class)

// Analyze a video snippet
val result = hailo.analyzeVideo(
    VideoVisionRequest(
        videoPath = "/tmp/clip.mp4",
        maxFrames = 8,
        ocr = true
    )
)

if (result.ok) {
    println("Scene detected: \")
    println("Objects: \")
    println("OCR Hints: \")
} else {
    println("Failed: \")
}
