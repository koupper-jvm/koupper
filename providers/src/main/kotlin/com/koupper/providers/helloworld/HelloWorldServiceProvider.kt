package com.koupper.providers.helloworld

import com.koupper.container.interfaces.Container
import com.koupper.providers.ServiceProvider

class HelloWorldServiceProvider(private val container: Container) : ServiceProvider {
    override fun up() {
        this.container.bind(HelloWorldProvider::class) {
            HelloWorldImpl()
        }
    }
}

class HelloWorldImpl : HelloWorldProvider {
    override fun ping(): HelloWorldResponse {
        return HelloWorldResponse(true, "pong from HelloWorld")
    }
}