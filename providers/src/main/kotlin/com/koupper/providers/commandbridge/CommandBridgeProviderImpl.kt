package com.koupper.providers.commandbridge

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

class CommandBridgeProviderImpl : CommandBridgeProvider {

    private var watchDir: File? = null
    private var watchService: WatchService? = null

    override fun watch(directory: File): CommandBridgeProvider {
        directory.mkdirs()
        val ws = FileSystems.getDefault().newWatchService()
        directory.toPath().register(ws, ENTRY_CREATE)
        watchDir = directory
        watchService = ws
        return this
    }

    override fun drain(): CommandBridgeProvider {
        watchDir?.listFiles { f -> f.name.endsWith(".response") }?.forEach { it.delete() }
        return this
    }

    override fun nextCommand(pollTimeoutMs: Long): String? {
        val ws  = watchService ?: return null
        val dir = watchDir     ?: return null

        val key = ws.poll(pollTimeoutMs, TimeUnit.MILLISECONDS) ?: return null

        var result: String? = null
        for (ev in key.pollEvents()) {
            if (ev.kind() == OVERFLOW) continue
            @Suppress("UNCHECKED_CAST")
            val fname = (ev as WatchEvent<Path>).context().fileName.toString()
            if (!fname.endsWith(".response")) continue

            val file    = File(dir, fname)
            val content = runCatching { file.readText().trim() }.getOrDefault("")
            file.delete()

            if (content.isNotBlank() && result == null) result = content
        }
        key.reset()
        return result
    }

    override fun close() {
        runCatching { watchService?.close() }
        watchService = null
        watchDir     = null
    }
}
