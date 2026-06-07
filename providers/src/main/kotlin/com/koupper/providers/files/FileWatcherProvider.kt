package com.koupper.providers.files

import java.io.File

enum class WatchEvent { CREATE, MODIFY, DELETE }

/**
 * Watches one or more directories for filesystem events.
 *
 * Usage:
 *   watcher().watch(
 *       dirs   = listOf(File("/tmp/inbox")),
 *       events = setOf(WatchEvent.CREATE),
 *   ) { dir, filename, event ->
 *       println("$event  $filename  in $dir")
 *   }
 *
 * The call blocks until [stop] returns true or the thread is interrupted.
 * Each directory is watched non-recursively; call [watchRecursive] for trees.
 */
interface FileWatcherProvider {

    /**
     * Watch [dirs] for [events]. Calls [onEvent] on the calling thread for each match.
     * Blocks until [stop] returns `true`.
     *
     * @param dirs           Directories to watch (must exist).
     * @param events         Event types to listen for.
     * @param pollTimeoutMs  How long to block waiting for an event before re-checking [stop].
     * @param stop           Checked after each poll — return `true` to exit cleanly.
     * @param onEvent        Called with (directory, filename, event kind).
     */
    fun watch(
        dirs: List<File>,
        events: Set<WatchEvent> = setOf(WatchEvent.CREATE, WatchEvent.MODIFY, WatchEvent.DELETE),
        pollTimeoutMs: Long = 500,
        stop: () -> Boolean = { false },
        onEvent: (dir: File, filename: String, event: WatchEvent) -> Unit,
    )

    /**
     * Convenience overload for a single directory.
     */
    fun watch(
        dir: File,
        events: Set<WatchEvent> = setOf(WatchEvent.CREATE, WatchEvent.MODIFY, WatchEvent.DELETE),
        pollTimeoutMs: Long = 500,
        stop: () -> Boolean = { false },
        onEvent: (dir: File, filename: String, event: WatchEvent) -> Unit,
    ) = watch(listOf(dir), events, pollTimeoutMs, stop, onEvent)
}
