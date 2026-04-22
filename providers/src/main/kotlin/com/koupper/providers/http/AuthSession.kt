package com.koupper.providers.http

data class AuthSession(
    val userId: String,
    val email: String,
    val role: String,
    val issuedAtEpochSeconds: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
    val token: String
)
