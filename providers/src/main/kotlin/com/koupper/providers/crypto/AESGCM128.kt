package com.koupper.providers.crypto

import com.koupper.os.env
import com.koupper.os.setGlobalConfig
import com.koupper.providers.Setup
import com.koupper.providers.files.FileHandlerImpl
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AESGCM128 : Crypt0, Setup() {
    private var secret: String = env("SHARED_SECRET")
    var IV: ByteArray = byteArrayOf()
    var authData: ByteArray = byteArrayOf()

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