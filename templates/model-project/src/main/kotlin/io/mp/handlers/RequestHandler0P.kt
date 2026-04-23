package io.mp.handlers

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent

{{FUNCTION_NAME}}

class {{REQUEST_HANDLER_NAME}} : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    override fun handleRequest(input: APIGatewayProxyRequestEvent?, context: Context?): APIGatewayProxyResponseEvent {
        val result: {{TYPE}} = {{FUNCTION_NAME}}()

        return APIGatewayProxyResponseEvent().apply {
            statusCode = 200
            headers = input?.headers?.toMap() ?: emptyMap()
            body = result
        }
    }
}
