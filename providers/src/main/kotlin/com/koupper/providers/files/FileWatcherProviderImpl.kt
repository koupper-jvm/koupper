package com.koupper.providers.files

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent as NioWatchEvent
import java.util.concurrent.TimeUnit

class FileWatcherProviderImpl : FileWatcherProvider {

    override fun watch(
        dirs: List<File>,
        events: Set<WatchEvent>,
        pollTimeoutMs: Long,
        stop: () -> Boolean,
        onEvent: (dir: File, filename: String, event: WatchEvent) -> Unit,
    ) {
        val nioKinds = buildList {
            if (WatchEvent.CREATE in events) add(ENTRY_CREATE)
            if (WatchEvent.MODIFY in events) add(ENTRY_MODIFY)
            if (WatchEvent.DELETE in events) add(ENTRY_DELETE)
        }
        if (nioKinds.isEmpty()) return

        val kindsArray = nioKinds.toTypedArray()

        val ws = FileSystems.getDefault().newWatchService()
        try {
            val keyToDir = mutableMapOf<java.nio.file.WatchKey, File>()

            for (dir in dirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val key = dir.toPath().register(ws, *kindsArray)
                keyToDir[key] = dir
            }

            while (!stop()) {
                val key = ws.poll(pollTimeoutMs, TimeUnit.MILLISECONDS) ?: continue
                val dir = keyToDir[key]
                if (dir == null) { key.cancel(); continue }

                for (ev in key.pollEvents()) {
                    val kind = when (ev.kind()) {
                        ENTRY_CREATE -> WatchEvent.CREATE
                        ENTRY_MODIFY -> WatchEvent.MODIFY
                        ENTRY_DELETE -> WatchEvent.DELETE
                        else         -> null
                    } ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val filename = (ev as? NioWatchEvent<Path>)?.context()?.fileName?.toString()
                        ?: continue
                    onEvent(dir, filename, kind)
                }

                if (!key.reset()) {
                    keyToDir.remove(key)
                    if (keyToDir.isEmpty()) break
                }
            }
        } finally {
            ws.close()
        }
    }
}
