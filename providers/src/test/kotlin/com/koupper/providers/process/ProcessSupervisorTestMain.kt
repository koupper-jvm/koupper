package com.koupper.providers.process

object ProcessSupervisorTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sleepMs = args.firstOrNull()?.toLongOrNull() ?: 30000L
        println("process-supervisor-test-main-started")
        Thread.sleep(sleepMs)
        println("process-supervisor-test-main-finished")
    }
}
