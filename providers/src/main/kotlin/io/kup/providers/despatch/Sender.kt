package io.kup.providers.despatch

interface Sender {
    fun configUsing(configPath: String): Sender

    fun withContent(content: String)

    fun sendTo(targetEmail: String): Boolean
}