package com.koupper.daemons

import kotlinx.coroutines.*
import java.nio.file.*

fun main() {
    val filePath = Paths.get("path/to/your/file.txt")
    val watchService = FileSystems.getDefault().newWatchService()
    val watchKey = filePath.parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

    val scope = CoroutineScope(Dispatchers.Default)
    var lastModified = filePath.toFile().lastModified()

    val fileWatcherJob = scope.launch {
        while (isActive) {
            val key = watchService.take()
            for (event in key.pollEvents()) {
                if (event.context() == filePath.fileName) {
                    val currentModified = filePath.toFile().lastModified()
                    if (currentModified != lastModified) {
                        lastModified = currentModified
                        println("File modified at ${java.time.LocalDateTime.now()}")
                    }
                }
            }
            key.reset()
        }
    }

    runBlocking {
        println("File watcher started. Press Enter to stop.")
        readLine()
        fileWatcherJob.cancelAndJoin()
        watchKey.cancel()
    }
}
