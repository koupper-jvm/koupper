package test.project

import javax.ws.rs.GET
import javax.ws.rs.Path

@Path("/greetings")
class Greetings {
    @get:GET
    val helloGreeting: String
        get() = "hello"
}