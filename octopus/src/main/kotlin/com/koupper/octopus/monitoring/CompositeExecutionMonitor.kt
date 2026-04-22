package com.koupper.octopus.monitoring

import com.koupper.shared.monitoring.ExecutionMeta
import com.koupper.shared.monitoring.ExecutionMonitor

class CompositeExecutionMonitor(
    private val delegates: List<ExecutionMonitor>
) : ExecutionMonitor {
    override fun <T> track(meta: ExecutionMeta, block: () -> T): T {
        return trackChain(meta, delegates, 0, block)
    }

    override fun reportPayload(key: String, payload: Any) {
        delegates.forEach { it.reportPayload(key, payload) }
    }

    private fun <T> trackChain(
        meta: ExecutionMeta,
        monitors: List<ExecutionMonitor>,
        idx: Int,
        block: () -> T
    ): T {
        if (idx >= monitors.size) return block()
        return monitors[idx].track(meta) {
            trackChain(meta, monitors, idx + 1, block)
        }
    }
}
