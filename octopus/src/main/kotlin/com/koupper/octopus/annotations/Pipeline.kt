package com.koupper.octopus.annotations

/**
 * Declares a cron-scheduled pipeline: the annotated agent runs first, then each name in
 * [chain] (comma-separated, without .kts) runs sequentially as subsequent stages.
 *
 * Example:
 * ```
 * @Pipeline(cron = "0 8 * * *", chain = "SummarizerAgent,TelegramNotifyAgent", id = "morning-digest")
 * @Export
 * val setup: () -> FeedResult = { ... }
 * ```
 *
 * A coordinator .kts is auto-generated and enqueued each time the cron fires.
 * The stages are isolated subprocesses — output is NOT piped between them; use shared
 * files, a queue, or environment state for inter-stage data.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Pipeline(
    val cron: String = "",
    val chain: String = "",   // comma-separated agent names (without .kts)
    val id: String = "",
    val delay: Long = 0L,     // one-shot: fire once after N milliseconds
    val rate: Long = 0L       // repeating: fire every N milliseconds
)
