package com.koupper.providers.telegram

/**
 * Bidirectional Telegram channel for agent communication.
 *
 * Connects a Telegram bot to the Koupper agent runtime via long-polling.
 * Incoming messages are delivered via [onMessage]; outgoing responses are
 * sent with [sendMessage].
 *
 * Configuration: token from BotFather, optional allowlist of chat IDs.
 *
 * Usage:
 *   val telegram = app.getInstance(TelegramChannelProvider::class)
 *   telegram.startPolling(token, allowedChatIds) { chatId, text ->
 *       // handle message
 *       telegram.sendMessage(token, chatId, "Hello!")
 *   }
 */
interface TelegramChannelProvider {

    /**
     * Starts long-polling for updates. Blocks the calling thread.
     * Call from a daemon thread or a dedicated coroutine.
     *
     * @param token        Telegram Bot API token from BotFather.
     * @param allowedChats Optional set of chat IDs to accept. Empty = accept all.
     * @param running      Checked on each poll cycle — set to false to stop.
     * @param offsetFile   Optional file for persisting the update offset across restarts.
     * @param onMessage    Called for each accepted message with (chatId, text).
     */
    fun startPolling(
        token: String,
        allowedChats: Set<Long> = emptySet(),
        running: () -> Boolean = { true },
        offsetFile: java.io.File? = null,
        onMessage: (chatId: Long, text: String) -> Unit
    )

    /**
     * Sends a text message to a Telegram chat.
     *
     * @param token  Telegram Bot API token.
     * @param chatId Target chat ID.
     * @param text   Message text (plain text, max 4096 chars per message).
     */
    fun sendMessage(token: String, chatId: Long, text: String)

    /**
     * Sends a photo to a Telegram chat via multipart upload.
     *
     * @param token   Telegram Bot API token.
     * @param chatId  Target chat ID.
     * @param file    Image file to send (JPEG/PNG/etc.).
     * @param caption Optional caption text (max 1024 chars).
     */
    fun sendPhoto(token: String, chatId: Long, file: java.io.File, caption: String = "")

    /**
     * Sends a long text split into chunks of [chunkSize] characters.
     * Telegram messages are limited to 4096 characters.
     */
    fun sendLongMessage(token: String, chatId: Long, text: String, chunkSize: Int = 3800) {
        if (text.length <= chunkSize) {
            sendMessage(token, chatId, text)
        } else {
            text.chunked(chunkSize).forEachIndexed { i, chunk ->
                if (i > 0) Thread.sleep(300)
                sendMessage(token, chatId, chunk)
            }
        }
    }
}
