package com.koupper.providers.hashing

interface Hasher {
    fun hash(password: String, salt: String): String
    fun verify(password: String, stored: String): Boolean
}
