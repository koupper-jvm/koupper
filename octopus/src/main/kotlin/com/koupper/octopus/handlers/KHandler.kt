package com.koupper.octopus.handlers

interface KHandler<Req : Any, Res : Any> {
    val name: String
    fun execute(req: Req): Res
}
