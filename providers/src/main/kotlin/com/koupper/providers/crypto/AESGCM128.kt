package com.koupper.providers.crypto

import com.koupper.container.interfaces.Container
import com.koupper.providers.extensions.getProperty
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AESGCM128(container: Container) : Crypt0 {
    private lateinit var secret: String
    var IV: ByteArray = byteArrayOf()
    var authData: ByteArray = byteArrayOf()

    init {
        val secretOnEnv = container.env("SHARED_SECRET")

        if (secretOnEnv === "undefined") {
            try {
                val secretOnFile = File(".env").getProperty("SHARED_SECRET")

                if (secretOnFile === "undefined") {
                    throw Exception("The SHARED_SECRET should be present in: an environment variable||an env file (.env) to use this provider")
                } else {
                    this.secret = secret
                }
            } catch (e: Exception) {
                throw Exception("The SHARED_SECRET should be present in: an environment variable||an env file (.env) to use this provider")
            }
        } else {
            this.secret = secretOnEnv
        }
    }

    override fun encrypt(rawText: ByteArray, authData: ByteArray): ByteArray {
        val IV = ByteArray(16)

        val secureRandom = SecureRandom()
        secureRandom.nextBytes(IV)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(this.secret.toByteArray(), "AES"),
            GCMParameterSpec(128, IV)
        )

        this.IV = IV
        this.authData = authData

        return cipher.doFinal(rawText.plus(authData))
    }

    override fun decrypt(ciphertext: ByteArray, IVbytes: ByteArray, authData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(this.secret.toByteArray(), "AES"),
            GCMParameterSpec(128, IVbytes)
        )

        this.IV = IVbytes
        this.authData = authData

        return cipher.doFinal(ciphertext.plus(authData))
    }

}