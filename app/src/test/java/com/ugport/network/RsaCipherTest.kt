package com.ugport.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import javax.crypto.Cipher

class RsaCipherTest {

    @Test
    fun encryptsWithSpkiKeyMislabeledAsRsaPublicKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val spki = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        // 绿联服务端真实返回：SPKI DER，但 PEM 标签写成 RSA PUBLIC KEY
        val pem = "-----BEGIN RSA PUBLIC KEY-----\n$spki\n-----END RSA PUBLIC KEY-----"

        val encrypted = RsaCipher.encrypt(pem, "test-password")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private as RSAPrivateKey)
        val plain = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
        assertEquals("test-password", plain)
    }
}
