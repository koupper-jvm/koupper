package com.koupper.providers.commandbridge

import java.io.File

interface CommandBridgeProvider {

    /**
     * Sets the directory to watch for incoming command files (*.response).
     * Creates the directory if it does not exist.
     */
    fun watch(directory: File): CommandBridgeProvider

    /**
     * Deletes any stale *.response files left from previous sessions.
     * Call immediately after watch() to avoid processing old commands.
     */
    fun drain(): CommandBridgeProvider

    /**
     * Blocks for up to [pollTimeoutMs] milliseconds waiting for the next
     * command file to appear. Returns the file content (trimmed) or null
     * if no command arrived within the timeout.
     *
     * The *.response file is deleted after reading.
     */
    fun nextCommand(pollTimeoutMs: Long = 500L): String?

    /**
     * Releases the underlying WatchService. Call when the command loop exits.
     */
    fun close()
}
