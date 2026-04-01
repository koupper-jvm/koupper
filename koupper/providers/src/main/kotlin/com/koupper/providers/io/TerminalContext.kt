package com.koupper.providers.io

object TerminalContext {
    private val current = ThreadLocal<TerminalIO?>()

    fun set(io: TerminalIO) = current.set(io)
    fun get(): TerminalIO? = current.get()
    fun clear() = current.remove()
}
