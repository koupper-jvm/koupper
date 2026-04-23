package io.mp.handlers

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.koupper.octopus.modules.aws.APIGPREInput

{{FUNCTION_NAME}}

class {{REQUEST_HANDLER_NAME}} : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    override fun handleRequest(input: APIGatewayProxyRequestEvent?, context: Context?): APIGatewayProxyResponseEvent {
        {{FUNCTION_NAME}}(APIGPREInput(
            version = null,
            resource = null,
            path = input?.path ?: "",
            httpMethod = null,
            headers = input?.headers,
            multiValueHeaders = input?.multiValueHeaders ?: emptyMap(),
            queryStringParameters = input?.queryStringParameters ?: emptyMap(),
            multiValueQueryStringParameters = input?.multiValueQueryStringParameters ?: emptyMap(),
            pathParameters = input?.pathParameters ?: emptyMap(),
            stageVariables = input?.stageVariables ?: emptyMap(),
            body = input?.body ?: "",
            isBase64Encoded = null
        ))

        return APIGatewayProxyResponseEvent().withStatusCode(204)
    }
}
