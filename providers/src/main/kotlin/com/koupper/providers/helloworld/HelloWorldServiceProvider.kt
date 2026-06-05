package com.koupper.providers.helloworld

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class HelloWorldServiceProvider() : ServiceProvider() {
    override fun up() {
        app.bind(HelloWorldProvider::class, {
            HelloWorldImpl()
        })
    }
}

class HelloWorldImpl : HelloWorldProvider {
    override fun ping(): HelloWorldResponse {
        return HelloWorldResponse(true, "pong from HelloWorld")
    }
}