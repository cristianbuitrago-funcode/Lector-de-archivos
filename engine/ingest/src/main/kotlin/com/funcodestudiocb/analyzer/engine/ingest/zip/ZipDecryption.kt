package com.funcodestudiocb.analyzer.engine.ingest.zip

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** La contraseña no es correcta para esta entrada. */
class WrongPasswordException : IOException("Contraseña incorrecta")

/** Los datos cifrados no superan la verificación de integridad. */
class IntegrityException(message: String) : IOException(message)

/**
 * Cifrado "tradicional" de PKWARE (ZipCrypto), APPNOTE 6.1. Solo descifrado.
 *
 * Es un cifrado débil (no protege de verdad los datos), pero es habitual en ZIP antiguos.
 */
internal object ZipCrypto {
    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    private class Keys(password: ByteArray) {
        var k0 = 0x12345678
        var k1 = 0x23456789
        var k2 = 0x34567890

        init {
            password.forEach { update(it.toInt() and 0xFF) }
        }

        fun update(b: Int) {
            k0 = crc(k0, b)
            k1 = (k1 + (k0 and 0xFF)) * 134775813 + 1
            k2 = crc(k2, k1 ushr 24)
        }

        fun streamByte(): Int {
            val temp = (k2 or 2) and 0xFFFF
            return ((temp * (temp xor 1)) ushr 8) and 0xFF
        }

        fun decrypt(c: Int): Int = (c xor streamByte()).also { update(it) }

        private fun crc(crc: Int, b: Int): Int = CRC_TABLE[(crc xor b) and 0xFF] xor (crc ushr 8)
    }

    /**
     * Devuelve un flujo descifrado tras validar la cabecera de 12 bytes. [checkByte] es el byte alto del CRC
     * o, si la entrada usa descriptor de datos, el byte alto de la hora DOS. Se prueban las codificaciones de
     * contraseña indicadas en orden.
     */
    fun decrypt(raw: InputStream, passwords: List<ByteArray>, checkByte: Int): InputStream {
        val header = ByteArray(12)
        readFully(raw, header)
        for (password in passwords) {
            val keys = Keys(password)
            var last = 0
            for (b in header) last = keys.decrypt(b.toInt() and 0xFF)
            if (last == checkByte) {
                return object : FilterInputStream(raw) {
                    override fun read(): Int {
                        val c = super.read()
                        return if (c < 0) c else keys.decrypt(c)
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        for (i in off until off + maxOf(n, 0)) b[i] = keys.decrypt(b[i].toInt() and 0xFF).toByte()
                        return n
                    }
                }
            }
        }
        throw WrongPasswordException()
    }
}

/** Cifrado AES de WinZip (AE-1/AE-2). Solo descifrado, con verificación HMAC-SHA1 al final. */
internal object WinZipAes {
    const val EXTRA_FIELD_ID = 0x9901
    private const val PASSWORD_VERIFIER = 2
    private const val AUTH_CODE = 10
    private const val ITERATIONS = 1000

    /** Datos del campo extra 0x9901. */
    data class Params(val vendorVersion: Int, val strength: Int, val actualMethod: Int) {
        val keyLength: Int get() = when (strength) {
            1 -> 16
            2 -> 24
            3 -> 32
            else -> throw IOException("Intensidad AES desconocida: $strength")
        }
        /** 8, 12 o 16 bytes para AES-128, -192 y -256. */
        val saltLength: Int get() = keyLength / 2
    }

    fun parseExtra(data: ByteArray): Params? {
        if (data.size < 7 || data[2] != 'A'.code.toByte() || data[3] != 'E'.code.toByte()) return null
        val version = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val strength = data[4].toInt() and 0xFF
        val method = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
        return Params(version, strength, method)
    }

    /** [compressedSize] incluye sal, verificador y código de autenticación. */
    fun decrypt(raw: InputStream, password: ByteArray, params: Params, compressedSize: Long): InputStream {
        val keyLength = params.keyLength
        val salt = ByteArray(params.saltLength).also { readFully(raw, it) }
        val verifier = ByteArray(PASSWORD_VERIFIER).also { readFully(raw, it) }
        val derived = pbkdf2HmacSha1(password, salt, ITERATIONS, 2 * keyLength + PASSWORD_VERIFIER)
        val expectedVerifier = derived.copyOfRange(2 * keyLength, 2 * keyLength + PASSWORD_VERIFIER)
        if (!MessageDigest.isEqual(verifier, expectedVerifier)) throw WrongPasswordException()

        val cipherLength = compressedSize - params.saltLength - PASSWORD_VERIFIER - AUTH_CODE
        if (cipherLength < 0) throw IntegrityException("Tamaño de datos AES inválido")
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, keyLength), "AES"))
        }
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(derived.copyOfRange(keyLength, 2 * keyLength), "HmacSHA1"))
        }
        derived.fill(0)
        return AesCtrStream(raw, aes, mac, cipherLength)
    }

    /** AES-CTR con contador little-endian que empieza en 1 (como WinZip/Gladman) y HMAC del texto cifrado. */
    private class AesCtrStream(
        private val raw: InputStream,
        private val aes: Cipher,
        private val mac: Mac,
        private var remaining: Long,
    ) : InputStream() {
        private val counter = ByteArray(16)
        private val keystream = ByteArray(16)
        private var used = 16
        private var verified = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) {
                verify()
                return -1
            }
            val n = raw.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw IntegrityException("Datos AES truncados")
            mac.update(b, off, n)
            for (i in off until off + n) {
                if (used == 16) nextBlock()
                b[i] = (b[i].toInt() xor keystream[used++].toInt()).toByte()
            }
            remaining -= n
            if (remaining == 0L) verify()
            return n
        }

        private fun nextBlock() {
            var i = 0
            while (i < 16) {
                counter[i] = (counter[i] + 1).toByte()
                if (counter[i].toInt() != 0) break
                i++
            }
            aes.doFinal(counter, 0, 16, keystream, 0)
            used = 0
        }

        private fun verify() {
            if (verified) return
            verified = true
            val stored = ByteArray(AUTH_CODE).also { readFully(raw, it) }
            val computed = mac.doFinal().copyOf(AUTH_CODE)
            if (!MessageDigest.isEqual(stored, computed)) throw IntegrityException("La autenticación AES no coincide")
        }
    }

    /** PBKDF2 con HMAC-SHA1 (RFC 8018) sobre los bytes exactos de la contraseña. */
    fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        // SecretKeySpec no admite claves vacías; en HMAC una clave vacía equivale a un byte cero (se rellena con ceros).
        val key = if (password.isEmpty()) byteArrayOf(0) else password
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, take)
            offset += take
            block++
        }
        return out
    }
}

internal fun readFully(input: InputStream, buffer: ByteArray) {
    var off = 0
    while (off < buffer.size) {
        val n = input.read(buffer, off, buffer.size - off)
        if (n < 0) throw IntegrityException("Datos cifrados truncados")
        off += n
    }
}
