# Dual-Deployment Architecture Design
## Local Server + AWS Lambda from the same handlers

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Run quizztea locally as an HTTP server and deploy to AWS Lambda + API Gateway without changing handler code — same `@Export @WebRoute` `.kt` file works in both modes.

**Architecture:** A new `RouteDispatcher` in koupper scans `@Export @WebRoute` annotations via reflection and dispatches requests without Vert.x. In server mode, `autoDiscover()` registers those same handlers in the Vert.x router. In Lambda mode, `LambdaEntryPoint` wraps `RouteDispatcher` and translates API Gateway events.

**Tech Stack:** Kotlin 2.0.20, Vert.x 4.5.8, `aws-lambda-java-core` (quizztea only), Kotlin reflection

---

## 1. Deployment Modes

```
LOCAL (developer machine)
─────────────────────────
Setup.kt
  └─ autoDiscover("io.quizztea")
       └─ scans @Export @WebRoute classes
            └─ registers each into Vert.x router
                 └─ HTTP server on localhost:8080

PROD (AWS)
──────────
API Gateway
  └─ LambdaEntryPoint.handleRequest(event, ctx)
       └─ RouteDispatcher.dispatch(method, path, requestCtx)
            └─ scans @Export @WebRoute classes (cold start, cached)
                 └─ calls matching KFunction directly
                      └─ returns APIGatewayProxyResponseEvent
```

The handler code (`@Export @WebRoute` `.kt` file) is identical in both paths.

---

## 2. Handler Format

All route handlers across all quizztea services adopt this format:

```kotlin
package io.quizztea.handlers.apps

import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.WebRoute
import com.koupper.shared.annotations.RouteMethod
import com.koupper.shared.runtime.WebResponse
import com.koupper.shared.runtime.RequestContext

@Export
@WebRoute(path = "/api/v1/apps", method = RouteMethod.GET)
val getApps: suspend (RequestContext) -> WebResponse = { ctx ->
    // business logic here
    WebResponse(mapOf("apps" to listOf<Any>()), 200)
}
```

Rules:
- One `@Export @WebRoute` per file
- Handler is a `val` with type `suspend (RequestContext) -> WebResponse`
- No Vert.x imports in handler files
- Per-route middlewares declared via `@Middleware` annotation on the handler:

```kotlin
@Export
@WebRoute(path = "/api/v1/apps", method = RouteMethod.GET)
@Middleware("request-log", "auth")
val getApps: suspend (RequestContext) -> WebResponse = { ctx -> ... }
```

`@Middleware` is a new annotation in `shared/annotations/`. `autoDiscover()` reads it and applies the middleware chain when registering the route in Vert.x. In Lambda mode, `@Middleware` is ignored — API Gateway authorizers handle auth.

---

## 3. New Pieces in koupper

### 3.1 `RequestContext` (shared module)

Framework-agnostic request envelope passed to every handler.

```kotlin
// shared/src/main/kotlin/com/koupper/shared/runtime/RequestContext.kt
data class RequestContext(
    val body: String?,
    val pathParams: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap()
)
```

### 3.2 `DispatchResult` (shared module)

```kotlin
// shared/src/main/kotlin/com/koupper/shared/runtime/DispatchResult.kt
data class DispatchResult(val statusCode: Int, val body: String)
```

### 3.3 `RouteScanner` (providers module)

Extracts scanning logic from `RuntimeRouterProvider.autoDiscover()` into a reusable utility.

```kotlin
// providers/src/main/kotlin/com/koupper/providers/runtime/router/RouteScanner.kt
object RouteScanner {
    // Returns map of "METHOD:path" -> KFunction<WebResponse>
    fun scan(packageName: String): Map<String, KFunction<*>>
}
```

Internally uses Kotlin reflection to:
1. Find all classes in `packageName` on the classpath
2. Filter those with both `@Export` and `@WebRoute` annotations
3. Return a map keyed by `"${webRoute.method}:${webRoute.path}"`

`RuntimeRouterProvider.autoDiscover()` is refactored to delegate scanning to `RouteScanner`. No breaking change to callers.

### 3.4 `RouteDispatcher` (providers module)

```kotlin
// providers/src/main/kotlin/com/koupper/providers/runtime/router/RouteDispatcher.kt
class RouteDispatcher(packageName: String) {
    private val routes: Map<String, KFunction<*>> by lazy {
        RouteScanner.scan(packageName)
    }

    suspend fun dispatch(method: String, path: String, ctx: RequestContext): DispatchResult {
        val key = "${method.uppercase()}:${path}"
        val handler = routes[key]
            ?: return DispatchResult(404, """{"error":"route not found","path":"$path"}""")
        return try {
            val response = handler.callSuspend(ctx) as WebResponse
            DispatchResult(response.statusCode, jacksonObjectMapper().writeValueAsString(response.body))
        } catch (e: Exception) {
            DispatchResult(500, """{"error":"${e.message}"}""")
        }
    }
}
```

---

## 4. Changes in quizztea services

Applies to **quizztea-api**, **auth-service**, and **quizztea-comms**.

### 4.1 Migrate route handlers

Each existing route in the DSL (`AppsRoutes.kt`, `AuthRoutes.kt`, etc.) becomes a standalone `.kt` file in a `handlers/` directory with `@Export @WebRoute`.

Before (DSL):
```kotlin
fun RuntimeRouterDsl.appsRoutes() {
    get<String> {
        path { "/api/v1/apps" }
        middlewares { listOf("request-log", "auth") }
        script { /* inline handler */ }
    }
}
```

After (handler file):
```kotlin
@Export
@WebRoute(path = "/api/v1/apps", method = RouteMethod.GET)
val getApps: suspend (RequestContext) -> WebResponse = { ctx ->
    /* same logic */
}
```

Old `*Routes.kt` DSL files are deleted after migration.

### 4.2 `Setup.kt` — switch to `autoDiscover`

```kotlin
// Before
registerRouter {
    appsRoutes()
    pollsRoutes()
    // ...
}

// After
autoDiscover("io.quizztea")  // or "com.quizztea.auth" / "com.quizztea.comms"
```

Per-route middlewares come from the `@Middleware` annotation on each handler. `autoDiscover` reads them and applies the middleware chain at registration time.

### 4.3 `LambdaEntryPoint.kt` — one per service

```kotlin
// quizztea-api: src/main/kotlin/server/LambdaEntryPoint.kt
class LambdaEntryPoint : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private val dispatcher = RouteDispatcher("io.quizztea")

    override fun handleRequest(
        event: APIGatewayProxyRequestEvent,
        ctx: Context
    ): APIGatewayProxyResponseEvent {
        val requestCtx = RequestContext(
            body = event.body,
            pathParams = event.pathParameters ?: emptyMap(),
            queryParams = event.queryStringParameters ?: emptyMap(),
            headers = event.headers ?: emptyMap()
        )
        val result = runBlocking {
            dispatcher.dispatch(event.httpMethod, event.path, requestCtx)
        }
        return APIGatewayProxyResponseEvent()
            .withStatusCode(result.statusCode)
            .withBody(result.body)
            .withHeaders(mapOf("Content-Type" to "application/json"))
    }
}
```

In Lambda mode, auth is handled by API Gateway authorizers — no `auth` middleware runs inside the Lambda.

---

## 5. Data Flow Comparison

| Step | Server mode | Lambda mode |
|------|-------------|-------------|
| Request arrives | Vert.x HTTP | API Gateway event |
| Routing | `autoDiscover` route map | `RouteDispatcher` route map |
| Middleware | koupper middleware chain | API Gateway authorizer |
| Handler call | Vert.x async dispatch | `callSuspend(ctx)` direct |
| Response | Vert.x response | `APIGatewayProxyResponseEvent` |

---

## 6. Error Handling

| Scenario | Server response | Lambda response |
|----------|----------------|-----------------|
| Route not found | Vert.x 404 | `DispatchResult(404, ...)` |
| Handler throws | Vert.x 500 | `DispatchResult(500, ...)` |
| Compile error (kts) | 500 + error body | 500 + error body |

All errors return JSON with `{"error": "..."}` body.

---

## 7. Testing

- `RouteScanner`: unit test — scan a test package with dummy `@Export @WebRoute` classes, assert map contents
- `RouteDispatcher`: unit test — mock scanner output, assert dispatch calls correct function and maps result
- `LambdaEntryPoint`: integration test — build a fake `APIGatewayProxyRequestEvent`, assert response status and body
- Handler files: unit test each handler independently with a `RequestContext` — no Vert.x or Lambda needed

---

## 8. Migration Order

1. Add `@Middleware` annotation to koupper `shared/annotations/`
2. Implement `RequestContext`, `DispatchResult`, `RouteScanner`, `RouteDispatcher` in koupper
3. Refactor `RuntimeRouterProvider.autoDiscover()` to use `RouteScanner` and read `@Middleware`
3. Run koupper tests — no regressions
4. Migrate quizztea-api handlers → `@Export @WebRoute` files
5. Update quizztea-api `Setup.kt` → `autoDiscover()`
6. Add quizztea-api `LambdaEntryPoint`
7. Repeat steps 4–6 for auth-service and quizztea-comms
8. Delete old `*Routes.kt` DSL files

Each step is independently testable before moving to the next.
