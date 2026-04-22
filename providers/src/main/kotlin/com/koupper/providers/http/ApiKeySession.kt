package com.koupper.providers.http

data class ApiKeySession(
    val appId: String,
    val appName: String,
    val scopes: List<String>,
    val createdBy: String
)
