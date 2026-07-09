# Dual-Deployment Lambda Entry Point — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `LambdaEntryPoint` to all 3 quizztea services so they run locally unchanged (Grizzly HTTP server) and can be deployed to AWS Lambda + API Gateway using the exact same route handlers.

**Architecture:** The existing koupper `RouteDispatcher` is already transport-agnostic — it dispatches requests through `GlobalRouteRegistry` without knowing about Grizzly or Lambda. `LambdaEntryPoint` runs the same route/middleware setup as `main()` (without starting the server), then translates each `APIGatewayProxyRequestEvent` into a `DispatchRequest`, calls `RouteDispatcher.dispatch()`, and converts `DispatchOutcome` back to `APIGatewayProxyResponseEvent`. No koupper changes required.

**Tech Stack:** Kotlin 2.0.20, `com.koupper.providers.runtime.router.RouteDispatcher`, `com.koupper.providers.runtime.router.DispatchRequest`, `com.koupper.providers.runtime.router.DispatchOutcome`, `aws-lambda-java-core:1.2.3`, `aws-lambda-java-events:3.14.0` (already in all 3 build.gradle files)

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `quizztea-api/src/main/kotlin/server/Setup.kt` | Modify | Extract `setupApplication(router)` from `main()` |
| `quizztea-api/src/main/kotlin/server/LambdaEntryPoint.kt` | Create | Lambda handler for quizztea-api |
| `quizztea-api/src/test/kotlin/server/LambdaEntryPointTest.kt` | Create | Integration test via `/api/v1/health` |
| `auth-service/src/main/kotlin/com/quizztea/auth/server/Setup.kt` | Modify | Extract `setupApplication(router)` |
| `auth-service/src/main/kotlin/com/quizztea/auth/server/LambdaEntryPoint.kt` | Create | Lambda handler for auth-service |
| `auth-service/src/test/kotlin/com/quizztea/auth/server/LambdaEntryPointTest.kt` | Create | Integration test via `/auth/health` |
| `quizztea-comms/src/main/kotlin/server/Setup.kt` | Modify | Extract `setupApplication(router)` |
| `quizztea-comms/src/main/kotlin/server/LambdaEntryPoint.kt` | Create | Lambda handler for quizztea-comms |
| `quizztea-comms/src/test/kotlin/server/LambdaEntryPointTest.kt` | Create | Integration test via `/api/v1/health` |

---

## Context: How the existing stack works

`GrizzlyRuntimeRouterProvider.registerRouter {}` and `registerMiddleware()` populate `GlobalRouteRegistry` (a static registry of routes + middleware closures). Then `RouteDispatcher.dispatch(DispatchRequest)` matches the request, runs middleware, invokes the handler, and returns `DispatchOutcome.Completed(status, payload)` or `DispatchOutcome.Stream(emitter)`.

`LambdaEntryPoint` just needs to populate `GlobalRouteRegistry` on cold start (same code as `main()`, minus `router.start()`) and then bridge the AWS event format to `DispatchRequest` on each invocation.

---

## Task 1: quizztea-api — Extract setup + add LambdaEntryPoint

**Files:**
- Modify: `quizztea-api/src/main/kotlin/server/Setup.kt`
- Create: `quizztea-api/src/main/kotlin/server/LambdaEntryPoint.kt`
- Create: `quizztea-api/src/test/kotlin/server/LambdaEntryPointTest.kt`

- [ ] **Step 1.1: Refactor Setup.kt — extract setupApplication()**

Replace the body of `main()` in `quizztea-api/src/main/kotlin/server/Setup.kt` with a call to a new `setupApplication()` function. The function contains everything except `router.start()` and the blocking join.

```kotlin
package server

import com.koupper.container.app
import com.koupper.logging.Appenders
import com.koupper.logging.LoggerContext
import com.koupper.logging.LoggerFactory
import com.koupper.octopus.createDefaultConfiguration
import com.koupper.providers.runtime.router.MiddlewareResult
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import com.koupper.shared.runtime.RuntimeServerInfo
import io.quiztea.http.domain.events.QuizParticipationInvited
import io.quiztea.http.domain.events.listeners.SendQuizInvitationEmailListener
import io.quiztea.http.routes.*
import server.middlewares.authMiddlewareFactory

const val BASE_URL = "http://0.0.0.0"
const val PORT = 8082

private val logger = LoggerFactory.get("quizztea-api").apply {
    clearAppenders(close = false)
    addAppender(Appenders.consoleJson())
}

fun setupApplication(router: RuntimeRouterProvider) {
    val globalEnv = com.koupper.os.envOptional("GLOBAL_ENV_FILE", System.getProperty("GLOBAL_ENV_FILE") ?: "")
    if (globalEnv.isNotEmpty()) {
        logger.info { "Manually setting global config from environment: $globalEnv" }
        com.koupper.os.setGlobalConfig(globalEnv)
    }

    createDefaultConfiguration()

    val bus = com.koupper.octopus.events.SimpleEventBus().apply {
        register(QuizParticipationInvited::class.java, SendQuizInvitationEmailListener())
    }
    com.koupper.octopus.filters.GlobalEventBus.bus = bus

    router.registerMiddleware("request-log") { ctx ->
        val requestId = java.util.UUID.randomUUID().toString()
        LoggerContext.put("service", "quizztea-api")
        LoggerContext.put("requestId", requestId)
        LoggerContext.put("method", ctx.method)
        LoggerContext.put("path", ctx.path)
        logger.info { "request_received" }
        MiddlewareResult(allowed = true, statusCode = 200, message = "")
    }
    router.registerMiddleware("auth") { ctx -> authMiddlewareFactory(requireJwt = true)(ctx) }
    router.registerMiddleware("policy:PollOwner") { ctx -> server.middlewares.pollOwnerPolicyMiddleware()(ctx) }
    router.registerMiddleware("policy:DraftPollOwner") { ctx -> server.middlewares.draftPollOwnerPolicyMiddleware()(ctx) }
    router.registerMiddleware("policy:QuizOwner") { ctx -> server.middlewares.quizOwnerPolicyMiddleware()(ctx) }
    router.registerMiddleware("policy:DraftQuizOwner") { ctx -> server.middlewares.draftQuizOwnerPolicyMiddleware()(ctx) }

    val corsOrigins = com.koupper.os.envOptional("CORS_ALLOWED_ORIGINS", "http://localhost:5173")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    router.registerRouter {
        cors {
            allowedOrigins = corsOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            allowedHeaders = listOf("Content-Type", "Authorization", "X-API-Key")
        }
        appsRoutes()
        pollsRoutes()
        quizzesRoutes()
        userRoutes()
        timelineRoutes()
        healthRoutes()
    }
}

fun main() {
    logger.info { "Starting quizztea-api on port $PORT" }
    try {
        val router = app.getInstance(RuntimeRouterProvider::class)
        setupApplication(router)
        router.start(PORT, "0.0.0.0")
        if (com.koupper.os.envOptional("SHUTDOWN_TYPE") == "INPUT") {
            logger.info { "Press any key to shutdown the server..." }
            readLine()
            logger.info { "Shutting down the server from input..." }
            router.stop()
        } else {
            logger.info { "Server running on $BASE_URL:$PORT" }
            Thread.currentThread().join()
        }
    } catch (e: Exception) {
        logger.error(e) { "Error starting server: ${e.message}" }
    }
}
```

Note: `ctx.method` replaces the old `ctx.attributes["http.method"] as? String ?: "UNKNOWN"` — `RequestContext.method` already holds the correct HTTP verb.

- [ ] **Step 1.2: Write the failing test**

Create `quizztea-api/src/test/kotlin/server/LambdaEntryPointTest.kt`:

```kotlin
package server

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaEntryPointTest {
    private val handler = LambdaEntryPoint()

    @Test
    fun `health route returns 200 with status ok`() {
        val event = APIGatewayProxyRequestEvent().apply {
            httpMethod = "GET"
            path = "/api/v1/health"
            headers = emptyMap()
            queryStringParameters = null
            body = null
        }
        val response = handler.handleRequest(event, null)
        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("ok"), "body should contain 'ok': ${response.body}")
    }

    @Test
    fun `unknown route returns 404`() {
        val event = APIGatewayProxyRequestEvent().apply {
            httpMethod = "GET"
            path = "/does-not-exist"
            headers = emptyMap()
        }
        val response = handler.handleRequest(event, null)
        assertEquals(404, response.statusCode)
    }
}
```

- [ ] **Step 1.3: Run the test — expect FAIL (class not found)**

```
cd C:\Users\jacob\develop\tdn_workspace\quizztea_workspace\quizztea.com
./gradlew :quizztea-api:test --tests "server.LambdaEntryPointTest" -i
```

Expected: compilation error — `LambdaEntryPoint` does not exist yet.

- [ ] **Step 1.4: Create LambdaEntryPoint.kt**

Create `quizztea-api/src/main/kotlin/server/LambdaEntryPoint.kt`:

```kotlin
package server

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.koupper.container.app
import com.koupper.providers.runtime.router.DispatchOutcome
import com.koupper.providers.runtime.router.DispatchRequest
import com.koupper.providers.runtime.router.RouteDispatcher
import com.koupper.providers.runtime.router.RuntimeRouterProvider

class LambdaEntryPoint : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private val routeDispatcher = RouteDispatcher()

    init {
        val router = app.getInstance(RuntimeRouterProvider::class)
        setupApplication(router)
    }

    override fun handleRequest(
        event: APIGatewayProxyRequestEvent,
        ctx: Context?
    ): APIGatewayProxyResponseEvent {
        val queryString = event.queryStringParameters
            ?.entries?.joinToString("&") { "${it.key}=${it.value}" }

        val request = DispatchRequest(
            method = event.httpMethod ?: "GET",
            path = event.path ?: "/",
            headers = event.headers?.mapValues { listOf(it.value) } ?: emptyMap(),
            queryString = queryString,
            contentType = event.headers?.get("Content-Type"),
            bodyProvider = { event.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0) }
        )

        return when (val outcome = routeDispatcher.dispatch(request)) {
            is DispatchOutcome.Completed -> {
                val (contentType, bytes) = routeDispatcher.serializePayload(outcome.payload, outcome.contentType)
                val headers = mutableMapOf("Content-Type" to contentType)
                headers.putAll(outcome.headers)
                APIGatewayProxyResponseEvent()
                    .withStatusCode(outcome.status)
                    .withBody(String(bytes, Charsets.UTF_8))
                    .withHeaders(headers)
            }
            is DispatchOutcome.Stream -> APIGatewayProxyResponseEvent()
                .withStatusCode(501)
                .withBody("""{"error":"SSE streaming not supported in Lambda mode"}""")
                .withHeaders(mapOf("Content-Type" to "application/json"))
        }
    }
}
```

- [ ] **Step 1.5: Run the test — expect PASS**

```
cd C:\Users\jacob\develop\tdn_workspace\quizztea_workspace\quizztea.com
./gradlew :quizztea-api:test --tests "server.LambdaEntryPointTest" -i
```

Expected: both tests pass.

- [ ] **Step 1.6: Commit**

```
git add quizztea-api/src/main/kotlin/server/Setup.kt
git add quizztea-api/src/main/kotlin/server/LambdaEntryPoint.kt
git add quizztea-api/src/test/kotlin/server/LambdaEntryPointTest.kt
git commit -m "feat(quizztea-api): add LambdaEntryPoint for AWS Lambda deployment"
```

---

## Task 2: auth-service — Extract setup + add LambdaEntryPoint

**Files:**
- Modify: `auth-service/src/main/kotlin/com/quizztea/auth/server/Setup.kt`
- Create: `auth-service/src/main/kotlin/com/quizztea/auth/server/LambdaEntryPoint.kt`
- Create: `auth-service/src/test/kotlin/com/quizztea/auth/server/LambdaEntryPointTest.kt`
- Modify: `auth-service/build.gradle.kts` (add testImplementation for kotlin-test if missing)

- [ ] **Step 2.1: Add test dependency to auth-service/build.gradle.kts**

Open `auth-service/build.gradle.kts` and add inside the `dependencies {}` block:

```kotlin
testImplementation(kotlin("test"))
```

And add inside the build file (after `dependencies {}`):

```kotlin
tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2.2: Fix AuthRoutes.kt — use ctx.method instead of attributes**

In `auth-service/src/main/kotlin/com/quizztea/auth/http/routes/AuthRoutes.kt`, the `buildProxyRequest` function uses `ctx.attributes["http.method"]` which is never set. Fix it:

```kotlin
fun buildProxyRequest(ctx: RequestContext, bodyString: String? = null): APIGatewayProxyRequestEvent {
    return APIGatewayProxyRequestEvent().apply {
        path = ctx.path
        httpMethod = ctx.method  // was: ctx.attributes["http.method"] as? String ?: "POST"
        headers = emptyMap()
        queryStringParameters = emptyMap()
        body = bodyString
    }
}
```

- [ ] **Step 2.3: Refactor Setup.kt — extract setupApplication()**

Replace `auth-service/src/main/kotlin/com/quizztea/auth/server/Setup.kt` with:

```kotlin
package com.quizztea.auth.server

import com.koupper.container.app
import com.koupper.logging.Appenders
import com.koupper.logging.LoggerContext
import com.koupper.logging.LoggerFactory
import com.koupper.octopus.createDefaultConfiguration
import com.koupper.os.envOptional
import com.koupper.providers.runtime.router.MiddlewareResult
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import com.koupper.shared.runtime.RuntimeServerInfo
import com.quizztea.auth.http.routes.authRoutes
import com.quizztea.auth.http.routes.healthRoutes

const val BASE_URL = "http://localhost"
const val PORT = 8081

private val logger = LoggerFactory.get("auth-service").apply {
    clearAppenders(close = false)
    addAppender(Appenders.consoleJson())
}

fun setupApplication(router: RuntimeRouterProvider) {
    val globalEnv = envOptional("GLOBAL_ENV_FILE", System.getProperty("GLOBAL_ENV_FILE") ?: "")
    if (globalEnv.isNotEmpty()) {
        logger.info { "Manually setting global config from environment: $globalEnv" }
        com.koupper.os.setGlobalConfig(globalEnv)
    }

    createDefaultConfiguration()

    router.registerMiddleware("request-log") { ctx ->
        val requestId = java.util.UUID.randomUUID().toString()
        LoggerContext.put("service", "auth-service")
        LoggerContext.put("requestId", requestId)
        LoggerContext.put("method", ctx.method)
        LoggerContext.put("path", ctx.path)
        logger.info { "request_received" }
        MiddlewareResult(allowed = true, statusCode = 200, message = "")
    }

    val corsOrigins = envOptional("CORS_ALLOWED_ORIGINS", "http://localhost:5173")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    router.registerRouter {
        cors {
            allowedOrigins = corsOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            allowedHeaders = listOf("Content-Type", "Authorization", "X-API-Key")
        }
        authRoutes()
        healthRoutes()
    }
}

fun main() {
    val router = app.getInstance(RuntimeRouterProvider::class)
    setupApplication(router)
    logger.info { "Starting Auth Service at $BASE_URL:$PORT" }
    try {
        router.start(PORT, "0.0.0.0")
        if (envOptional("SHUTDOWN_TYPE") == "INPUT") {
            logger.info { "Press any key to shutdown the server..." }
            readLine()
            logger.info { "Shutting down the server from input..." }
            router.stop()
        } else {
            logger.info { "Server is running on $BASE_URL:$PORT." }
            Thread.currentThread().join()
        }
    } catch (e: Exception) {
        logger.error(e) { "Error starting server: ${e.message}" }
    }
}
```

- [ ] **Step 2.4: Write the failing test**

Create `auth-service/src/test/kotlin/com/quizztea/auth/server/LambdaEntryPointTest.kt`:

```kotlin
package com.quizztea.auth.server

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaEntryPointTest {
    private val handler = LambdaEntryPoint()

    @Test
    fun `health route returns 200`() {
        val event = APIGatewayProxyRequestEvent().apply {
            httpMethod = "GET"
            path = "/api/v1/health"
            headers = emptyMap()
            queryStringParameters = null
            body = null
        }
        val response = handler.handleRequest(event, null)
        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("ok"), "body should contain 'ok': ${response.body}")
    }

    @Test
    fun `unknown route returns 404`() {
        val event = APIGatewayProxyRequestEvent().apply {
            httpMethod = "GET"
            path = "/does-not-exist"
            headers = emptyMap()
        }
        val response = handler.handleRequest(event, null)
        assertEquals(404, response.statusCode)
    }
}
```

All three services use `/api/v1/health` — confirmed from source.

- [ ] **Step 2.5: Run the test — expect FAIL**

```
cd C:\Users\jacob\develop\tdn_workspace\quizztea_workspace\quizztea.com
./gradlew :auth-service:test --tests "com.quizztea.auth.server.LambdaEntryPointTest" -i
```

Expected: compilation error — `LambdaEntryPoint` does not exist yet.

- [ ] **Step 2.6: Create LambdaEntryPoint.kt**

Create `auth-service/src/main/kotlin/com/quizztea/auth/server/LambdaEntryPoint.kt`:

```kotlin
package com.quizztea.auth.server

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.koupper.container.app
import com.koupper.providers.runtime.router.DispatchOutcome
import com.koupper.providers.runtime.router.DispatchRequest
import com.koupper.providers.runtime.router.RouteDispatcher
import com.koupper.providers.runtime.router.RuntimeRouterProvider

class LambdaEntryPoint : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private val routeDispatcher = RouteDispatcher()

    init {
        val router = app.getInstance(RuntimeRouterProvider::class)
        setupApplication(router)
    }

    override fun handleRequest(
        event: APIGatewayProxyRequestEvent,
        ctx: Context?
    ): APIGatewayProxyResponseEvent {
        val queryString = event.queryStringParameters
            ?.entries?.joinToString("&") { "${it.key}=${it.value}" }

        val request = DispatchRequest(
            method = event.httpMethod ?: "GET",
            path = event.path ?: "/",
            headers = event.headers?.mapValues { listOf(it.value) } ?: emptyMap(),
            queryString = queryString,
            contentType = event.headers?.get("Content-Type"),
            bodyProvider = { event.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0) }
        )

        return when (val outcome = routeDispatcher.dispatch(request)) {
            is DispatchOutcome.Completed -> {
                val (contentType, bytes) = routeDispatcher.serializePayload(outcome.payload, outcome.contentType)
                val headers = mutableMapOf("Content-Type" to contentType)
                headers.putAll(outcome.headers)
                APIGatewayProxyResponseEvent()
                    .withStatusCode(outcome.status)
                    .withBody(String(bytes, Charsets.UTF_8))
                    .withHeaders(headers)
            }
            is DispatchOutcome.Stream -> APIGatewayProxyResponseEvent()
                .withStatusCode(501)
                .withBody("""{"error":"SSE streaming not supported in Lambda mode"}""")
                .withHeaders(mapOf("Content-Type" to "application/json"))
        }
    }
}
```

- [ ] **Step 2.7: Run the test — expect PASS**

```
./gradlew :auth-service:test --tests "com.quizztea.auth.server.LambdaEntryPointTest" -i
```

Expected: both tests pass.

- [ ] **Step 2.8: Commit**

```
git add auth-service/src/main/kotlin/com/quizztea/auth/server/Setup.kt
git add auth-service/src/main/kotlin/com/quizztea/auth/server/LambdaEntryPoint.kt
git add auth-service/src/main/kotlin/com/quizztea/auth/http/routes/AuthRoutes.kt
git add auth-service/src/test/kotlin/com/quizztea/auth/server/LambdaEntryPointTest.kt
git add auth-service/build.gradle.kts
git commit -m "feat(auth-service): add LambdaEntryPoint for AWS Lambda deployment"
```

---

## Task 3: quizztea-comms — Extract setup + add LambdaEntryPoint

**Files:**
- Modify: `quizztea-comms/src/main/kotlin/server/Setup.kt`
- Create: `quizztea-comms/src/main/kotlin/server/LambdaEntryPoint.kt`
- Create: `quizztea-comms/src/test/kotlin/server/LambdaEntryPointTest.kt`

- [ ] **Step 3.1: Refactor Setup.kt — extract setupApplication()**

Replace `quizztea-comms/src/main/kotlin/server/Setup.kt` with:

```kotlin
package server

import com.koupper.container.app
import com.koupper.logging.Appenders
import com.koupper.logging.LoggerContext
import com.koupper.logging.LoggerFactory
import com.koupper.octopus.createDefaultConfiguration
import com.koupper.providers.runtime.router.MiddlewareResult
import com.koupper.providers.runtime.router.RuntimeRouterProvider
import com.koupper.shared.runtime.RuntimeServerInfo
import com.quizztea.comms.http.routes.commsRoutes
import com.quizztea.comms.http.routes.healthRoutes

const val BASE_URL = "http://0.0.0.0"
const val PORT = 8083

private val logger = LoggerFactory.get("quizztea-comms").apply {
    clearAppenders(close = false)
    addAppender(Appenders.consoleJson())
}

fun setupApplication(router: RuntimeRouterProvider) {
    createDefaultConfiguration()

    router.registerMiddleware("request-log") { ctx ->
        val requestId = java.util.UUID.randomUUID().toString()
        LoggerContext.put("service", "quizztea-comms")
        LoggerContext.put("requestId", requestId)
        LoggerContext.put("method", ctx.method)
        LoggerContext.put("path", ctx.path)
        logger.info { "request_received" }
        MiddlewareResult(allowed = true, statusCode = 200, message = "")
    }
    router.registerMiddleware("auth") { ctx -> server.middlewares.authMiddlewareFactory(requireJwt = true)(ctx) }
    router.registerMiddleware("policy:QuizOwner") { ctx -> server.middlewares.quizOwnerPolicyMiddleware()(ctx) }

    val corsOrigins = com.koupper.os.envOptional("CORS_ALLOWED_ORIGINS", "http://localhost:5173")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    router.registerRouter {
        cors {
            allowedOrigins = corsOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            allowedHeaders = listOf("Content-Type", "Authorization", "X-API-Key")
        }
        commsRoutes()
        healthRoutes()
    }
}

fun main() {
    val router = app.getInstance(RuntimeRouterProvider::class)
    setupApplication(router)
    logger.info { "Starting Comms Service at $BASE_URL:$PORT" }
    try {
        router.start(PORT, "0.0.0.0")
        if (com.koupper.os.envOptional("SHUTDOWN_TYPE") == "INPUT") {
            logger.info { "Press any key to shutdown the server..." }
            readLine()
            logger.info { "Shutting down the server from input..." }
            router.stop()
        } else {
            logger.info { "Server is running on $BASE_URL:$PORT." }
            Thread.currentThread().join()
        }
    } catch (e: Exception) {
        logger.error(e) { "Error starting server: ${e.message}" }
    }
}
```

- [ ] **Step 3.2: Write the failing test**

Create `quizztea-comms/src/test/kotlin/server/LambdaEntryPointTest.kt`:

```kotlin
package server

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaEntryPointTest {
    private val handler = LambdaEntryPoint()

    @Test
    fun `health route returns 200`() {
        val event = APIGatewayProxyRequestEvent().apply {
            httpMethod = "GET"
            path = "/api/v1/health"
            headers = emptyMap()
            queryStringParameters = null
            body = null
        }
        val response = handler.handleRequest(event, null)
        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("ok"), "body should contain 'ok': ${response.body}")
    }

    @Test
    fun `unknown route returns 404`() {
        val event = APIGatewayProxyRequestEvent().apply {
            httpMethod = "GET"
            path = "/does-not-exist"
            headers = emptyMap()
        }
        val response = handler.handleRequest(event, null)
        assertEquals(404, response.statusCode)
    }
}
```

Path confirmed: `/api/v1/health` in all three services.

- [ ] **Step 3.3: Run the test — expect FAIL**

```
./gradlew :quizztea-comms:test --tests "server.LambdaEntryPointTest" -i
```

Expected: compilation error — `LambdaEntryPoint` does not exist yet.

- [ ] **Step 3.4: Create LambdaEntryPoint.kt**

Create `quizztea-comms/src/main/kotlin/server/LambdaEntryPoint.kt`:

```kotlin
package server

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.koupper.container.app
import com.koupper.providers.runtime.router.DispatchOutcome
import com.koupper.providers.runtime.router.DispatchRequest
import com.koupper.providers.runtime.router.RouteDispatcher
import com.koupper.providers.runtime.router.RuntimeRouterProvider

class LambdaEntryPoint : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private val routeDispatcher = RouteDispatcher()

    init {
        val router = app.getInstance(RuntimeRouterProvider::class)
        setupApplication(router)
    }

    override fun handleRequest(
        event: APIGatewayProxyRequestEvent,
        ctx: Context?
    ): APIGatewayProxyResponseEvent {
        val queryString = event.queryStringParameters
            ?.entries?.joinToString("&") { "${it.key}=${it.value}" }

        val request = DispatchRequest(
            method = event.httpMethod ?: "GET",
            path = event.path ?: "/",
            headers = event.headers?.mapValues { listOf(it.value) } ?: emptyMap(),
            queryString = queryString,
            contentType = event.headers?.get("Content-Type"),
            bodyProvider = { event.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0) }
        )

        return when (val outcome = routeDispatcher.dispatch(request)) {
            is DispatchOutcome.Completed -> {
                val (contentType, bytes) = routeDispatcher.serializePayload(outcome.payload, outcome.contentType)
                val headers = mutableMapOf("Content-Type" to contentType)
                headers.putAll(outcome.headers)
                APIGatewayProxyResponseEvent()
                    .withStatusCode(outcome.status)
                    .withBody(String(bytes, Charsets.UTF_8))
                    .withHeaders(headers)
            }
            is DispatchOutcome.Stream -> APIGatewayProxyResponseEvent()
                .withStatusCode(501)
                .withBody("""{"error":"SSE streaming not supported in Lambda mode"}""")
                .withHeaders(mapOf("Content-Type" to "application/json"))
        }
    }
}
```

- [ ] **Step 3.5: Run the test — expect PASS**

```
./gradlew :quizztea-comms:test --tests "server.LambdaEntryPointTest" -i
```

Expected: both tests pass.

- [ ] **Step 3.6: Commit**

```
git add quizztea-comms/src/main/kotlin/server/Setup.kt
git add quizztea-comms/src/main/kotlin/server/LambdaEntryPoint.kt
git add quizztea-comms/src/test/kotlin/server/LambdaEntryPointTest.kt
git commit -m "feat(quizztea-comms): add LambdaEntryPoint for AWS Lambda deployment"
```

---

## Task 4: Verify server mode still works

After all three tasks, confirm that local server mode is unaffected.

- [ ] **Step 4.1: Run full test suite for all services**

```
cd C:\Users\jacob\develop\tdn_workspace\quizztea_workspace\quizztea.com
./gradlew :quizztea-api:test :auth-service:test :quizztea-comms:test
```

Expected: all tests pass.

- [ ] **Step 4.2: Verify server starts correctly (quizztea-api)**

```
./gradlew :quizztea-api:run
```

Expected: server starts on port 8082, logs show `Starting quizztea-api on port 8082`, GET `http://localhost:8082/api/v1/health` returns `{"status":"ok"}`.

Kill with Ctrl+C. Do not leave it running.

- [ ] **Step 4.3: Merge to develop**

```
git checkout develop
git merge --no-ff feature/lambda-entrypoint
git push origin develop
```

---

## Notes for Lambda deployment

To deploy a service to AWS Lambda:
1. Build a fat JAR: `./gradlew :quizztea-api:shadowJar` (or configure a shadow/zip plugin for Lambda packaging)
2. Set Lambda handler to: `server.LambdaEntryPoint`
3. Set env vars: `GLOBAL_ENV_FILE`, `CORS_ALLOWED_ORIGINS`, plus all DB/AWS credentials your handlers need
4. Configure API Gateway proxy integration pointing to the Lambda

The `LambdaEntryPoint` class name is the handler identifier in the Lambda console.
