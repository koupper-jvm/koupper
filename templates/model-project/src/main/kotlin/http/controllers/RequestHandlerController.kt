package http.controllers

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import io.mp.handlers.*
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.UriInfo

@Path("ROOT_PATH")
@Produces
@Consumes
class RequestHandlerController {
    @Context
    private lateinit var uriInfo: UriInfo

    @Context
    private lateinit var inputHeaders: HttpHeaders

    @POST
    @Path("/{path-param}")
    @Consumes
    @Produces
    fun postMethod(bodyJson: String): APIGatewayProxyResponseEvent {
        val apiGatewayProxyRequestEvent = APIGatewayProxyRequestEvent().apply {
            path = uriInfo.path
            httpMethod = "POST"
            headers = inputHeaders.requestHeaders.mapValues { it.value.joinToString(",") }
            queryStringParameters = uriInfo.queryParameters.mapValues { it.value.joinToString(",") }
            body = bodyJson
        }

        val {{REQUEST_HANDLER_VARIABLE_NAME}} = {{REQUEST_HANDLER_NAME}}()

        return {{REQUEST_HANDLER_VARIABLE_NAME}}.handleRequest(apiGatewayProxyRequestEvent, null)
    }
}
