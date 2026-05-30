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
     * @param onMessage    Called for each accepted message with (chatId, text).
     */
    fun startPolling(
        token: String,
        allowedChats: Set<Long> = emptySet(),
        running: () -> Boolean = { true },
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
     * Sends a long text split into chunks of [chunkSize] characters.
     * Telegram messages are limited to 4096 characters.
     */
    fun sendLongMessage(token: String, chatId: Long, text: String, chunkSize: Int = 3800) {
        if (text.length <= chunkSize) {
            sendMessage(token, chatId, text)
        } else {
            text.chunked(chunkSize).forEach { chunk ->
                sendMessage(token, chatId, chunk)
                Thread.sleep(300) // avoid flood limits
            }
        }
    }
}
