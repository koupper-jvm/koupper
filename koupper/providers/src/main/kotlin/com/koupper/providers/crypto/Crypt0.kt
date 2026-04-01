package com.koupper.providers.crypto

interface Crypt0 {
    fun encrypt(rawText: ByteArray, authData: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray, IVbytes: ByteArray, authData: ByteArray): ByteArray
}