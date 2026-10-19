package com.ugport.network

import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 绿联 UGOS 前端使用 jsrsasign 的 RSA/ECB/PKCS1Padding 加密密码，
 * 服务端 /v1/verify/check 返回的 X-Rsa-Token 是 Base64 编码的 PEM 公钥。
 */
object RsaCipher {

    fun encrypt(publicKeyPem: String, plaintext: String): String {
        val key = parsePublicKey(publicKeyPem)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun parsePublicKey(raw: String): PublicKey {
        val pem = normalize(raw)
        val der = when {
            pem.contains("BEGIN RSA PUBLIC KEY") ->
                Base64.getDecoder().decode(extractBody(pem, "RSA PUBLIC KEY"))
            pem.contains("BEGIN PUBLIC KEY") ->
                Base64.getDecoder().decode(extractBody(pem, "PUBLIC KEY"))
            else ->
                Base64.getDecoder().decode(pem.replace(Regex("\\s"), ""))
        }
        return parseDer(der)
    }

    /**
     * 绿联服务端会把 SubjectPublicKeyInfo（SPKI）的 DER 标成 BEGIN RSA PUBLIC KEY，
     * 所以不能只看 PEM 标签，先按 SPKI 解析，失败再按 PKCS#1 解析。
     */
    private fun parseDer(der: ByteArray): PublicKey {
        runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }.onSuccess { return it }
        return parsePkcs1(der)
    }

    /** 服务端返回的 header 可能是 URL 编码或带字面量 \n 的 PEM。 */
    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.contains('%')) {
            value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        value = value.replace("\\n", "\n").replace("\\r", "")
        if (value.startsWith("data:")) value = value.substringAfter(",", value)
        return value.trim()
    }

    private fun extractBody(pem: String, label: String): String {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val body = pem.substringAfter(begin, pem).substringBefore(end, pem)
        return body.replace(Regex("\\s"), "")
    }

    /** 解析 PKCS#1 RSAPublicKey: SEQUENCE { INTEGER modulus, INTEGER exponent } */
    private fun parsePkcs1(der: ByteArray): PublicKey {
        val reader = DerReader(der)
        reader.readSequence()
        val modulus = reader.readInteger()
        val exponent = reader.readInteger()
        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    private class DerReader(private val bytes: ByteArray) {
        private var pos = 0

        fun readSequence() {
            require(readTag() == 0x30) { "Not a DER sequence" }
            skipLength()
        }

        fun readInteger(): BigInteger {
            require(readTag() == 0x02) { "Not a DER integer" }
            val len = readLength()
            val value = bytes.copyOfRange(pos, pos + len)
            pos += len
            return BigInteger(1, value)
        }

        private fun readTag(): Int = bytes[pos++].toInt() and 0xFF

        private fun skipLength() {
            readLength()
        }

        private fun readLength(): Int {
            val first = bytes[pos++].toInt() and 0xFF
            if (first and 0x80 == 0) return first
            val count = first and 0x7F
            var length = 0
            repeat(count) { length = (length shl 8) or (bytes[pos++].toInt() and 0xFF) }
            return length
        }
    }
}
