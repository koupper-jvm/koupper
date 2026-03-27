package com.koupper.cli.modules

data class ApiConfig(
    var version: String? = null,
    var description: String? = null,
    var server: ServerConfig? = null,
    var controllers: List<ControllerEntry>? = null,
    var logging: LoggingConfig? = null
)

data class ServerConfig(
    var port: Int? = null,
    var contextPath: String? = null
)

data class ControllerEntry(
    var name: String? = null,
    var path: String? = null,
    var apis: List<ApiEntry>? = null
)

data class ApiEntry(
    var name: String? = null,
    var path: String? = null,
    var method: String? = null,
    var handler: String? = null,
    var description: String? = null
)

data class LoggingConfig(
    var level: String? = null,
    var output: String? = null
)
