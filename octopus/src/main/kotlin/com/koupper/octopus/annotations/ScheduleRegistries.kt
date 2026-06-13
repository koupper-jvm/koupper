package com.koupper.octopus.annotations

/**
 * Aggregates all runtime schedule registries.
 * Scripts import this single class instead of each Setup individually,
 * avoiding K2 FIR module-accessibility false positives on internal Java types.
 */
object ScheduleRegistries {
    fun all(): List<Map<String, Any?>> =
        ScheduledSetup.scheduleRegistry.values.toList() +
        TimerSetup.timerRegistry.values.toList() +
        ReactiveSetup.triggerRegistry.values.toList()
}
