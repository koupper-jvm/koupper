package http.controllers

import jakarta.ws.rs.*
import server.executor
import java.util.Collections.emptyMap

@Path("ROOT_PATH")
@Produces
@Consumes
class ModelController {
    @POST
    @Path("/{path-param}")
    @Consumes
    @Produces
    fun postMethod(
        @BeanParam
        @PathParam("path-param")
        @QueryParam("query-param")
        @MatrixParam("matrix-param")
        @HeaderParam("header-param")
        @CookieParam("cookie-param")
        @FormParam("form-param") params: Any
    ): Any {
        return executor.call({{FUNCTION_NAME}}, emptyMap())
    }
}
