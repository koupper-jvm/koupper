package test.project

import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

@Path("helloWorld")
class HelloWorldResource {
    @GET
    fun helloWorld() = "Hello World"

    data class HelloJson(val prop1: Int, val prop2: String)

    @GET
    @Path("json")
    @Produces(MediaType.APPLICATION_JSON)
    fun helloJson() = HelloJson(1, "test")
}
