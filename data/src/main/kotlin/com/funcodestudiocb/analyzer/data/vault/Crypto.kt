package com.funcodestudiocb.analyzer.data.vault

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Parámetros de derivación de la clave a partir de la contraseña (Argon2id). Se guardan junto a la bóveda. */
@Serializable
data class KdfParams(
    val algorithm: String = ALGORITHM,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: String,
) {
    companion object {
        const val ALGORITHM = "argon2id"
    }
}

/** Valores por defecto de Argon2id. Los parámetros reales quedan guardados, así que pueden endurecerse más adelante. */
data class KdfProfile(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** 64 MiB, 3 pasadas: por encima del mínimo recomendado por OWASP (19 MiB, 2 pasadas). */
        val DEFAULT = KdfProfile(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)
    }
}

/** Deriva claves de 256 bits a partir de una contraseña con Argon2id. */
object PasswordKdf {

    const val SALT_BYTES = 16
    const val KEY_BYTES = 32

    fun newParams(profile: KdfProfile, random: SecureRandom = SecureRandom()): KdfParams {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return KdfParams(
            memoryKiB = profile.memoryKiB,
            iterations = profile.iterations,
            parallelism = profile.parallelism,
            salt = Base64Codec.encode(salt),
        )
    }

    fun derive(password: CharArray, params: KdfParams): ByteArray {
        require(params.algorithm == KdfParams.ALGORITHM) { "Algoritmo de derivación no admitido" }
        require(params.memoryKiB in 1024..(1024 * 1024) && params.iterations in 1..20 && params.parallelism in 1..8) {
            "Parámetros de derivación fuera de rango"
        }
        val passwordBytes = utf8(password)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(params.memoryKiB)
                    .withIterations(params.iterations)
                    .withParallelism(params.parallelism)
                    .withSalt(Base64Codec.decode(params.salt))
                    .build(),
            )
            val out = ByteArray(KEY_BYTES)
            generator.generateBytes(passwordBytes, out)
            return out
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun utf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        // Borra la copia intermedia del codificador.
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}

/** Cifrado autenticado AES-256-GCM con nonce aleatorio de 96 bits. Formato: nonce || texto cifrado || etiqueta. */
object Aead {
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(key.size == 32) { "Se requiere una clave de 256 bits" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Lanza [javax.crypto.AEADBadTagException] si la clave es incorrecta o los datos fueron alterados. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Se requiere una clave de 256 bits" }
        require(sealed.size > NONCE_BYTES + TAG_BITS / 8) { "Datos cifrados demasiado cortos" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }
}

/** Base64 sin dependencias de Android (para poder probarse en la JVM). */
object Base64Codec {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
