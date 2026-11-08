package com.funcodestudiocb.analyzer.data.vault

import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTest {

    private val fast = KdfProfile(memoryKiB = 1024, iterations = 1, parallelism = 1)

    @Test
    fun argon2idEsDeterministaConLaMismaSalYDistintoConOtra() {
        val params = PasswordKdf.newParams(fast)
        val a = PasswordKdf.derive("clave".toCharArray(), params)
        val b = PasswordKdf.derive("clave".toCharArray(), params)
        val c = PasswordKdf.derive("clave".toCharArray(), PasswordKdf.newParams(fast))
        assertContentEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(32, a.size)
    }

    @Test
    fun argon2idVectorDeReferenciaRfc9106() {
        // RFC 9106, sección 5.3 (Argon2id) adaptada: comprobación de que se usa la variante y versión correctas
        // comparando con el mismo cálculo hecho a mano con la API de Bouncy Castle.
        val params = KdfParams(memoryKiB = 1024, iterations = 2, parallelism = 1, salt = Base64Codec.encode(ByteArray(16) { 1 }))
        val ours = PasswordKdf.derive("password".toCharArray(), params)
        val reference = ByteArray(32)
        org.bouncycastle.crypto.generators.Argon2BytesGenerator().apply {
            init(
                org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                    .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(1024).withIterations(2).withParallelism(1)
                    .withSalt(ByteArray(16) { 1 }).build(),
            )
        }.generateBytes("password".toByteArray(), reference)
        assertContentEquals(reference, ours)
    }

    @Test
    fun parametrosDeArgon2FueraDeRangoSeRechazan() {
        val bad = PasswordKdf.newParams(fast).copy(memoryKiB = 1)
        assertFailsWith<IllegalArgumentException> { PasswordKdf.derive("x".toCharArray(), bad) }
        assertFailsWith<IllegalArgumentException> { PasswordKdf.derive("x".toCharArray(), bad.copy(memoryKiB = 1024, algorithm = "pbkdf2")) }
    }

    @Test
    fun aesGcmDetectaClaveIncorrectaAlteracionYAadDistinto() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val sealed = Aead.seal(key, "secreto".toByteArray(), "aad".toByteArray())
        assertContentEquals("secreto".toByteArray(), Aead.open(key, sealed, "aad".toByteArray()))

        val otherKey = ByteArray(32).also(SecureRandom()::nextBytes)
        assertFailsWith<AEADBadTagException> { Aead.open(otherKey, sealed, "aad".toByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<AEADBadTagException> { Aead.open(key, tampered, "aad".toByteArray()) }
        assertFailsWith<AEADBadTagException> { Aead.open(key, sealed, "otro".toByteArray()) }
    }

    @Test
    fun cadaCifradoUsaUnNonceDistinto() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val a = Aead.seal(key, "x".toByteArray(), byteArrayOf())
        val b = Aead.seal(key, "x".toByteArray(), byteArrayOf())
        assertFalse(a.copyOfRange(0, Aead.NONCE_BYTES).contentEquals(b.copyOfRange(0, Aead.NONCE_BYTES)))
    }

    @Test
    fun claveEnBrutoDeSqlcipher() {
        val key = ByteArray(32) { it.toByte() }
        val literal = String(FuncodeDatabase.rawKeyLiteral(key))
        assertEquals("x'" + (0 until 32).joinToString("") { "%02x".format(it) } + "'", literal)
        assertTrue(literal.length == 67)
    }

    @Test
    fun politicaDeContrasenas() {
        fun issues(s: String) = PasswordPolicy.check(s.toCharArray())
        assertTrue(PasswordIssue.TOO_SHORT in issues("Ab1!"))
        assertTrue(PasswordIssue.TOO_SIMPLE in issues("abcdefghxy"))
        assertTrue(PasswordIssue.TRIVIAL in issues("aaaaaaaaaaaaaaaaaaaa"))
        assertTrue(PasswordIssue.TRIVIAL in issues("12345678"))
        assertTrue(PasswordIssue.TOO_LONG in issues("a1".repeat(100)))
        assertTrue(issues("Funcode-2026").isEmpty())
        // Una frase larga es válida sin números ni símbolos.
        assertTrue(issues("mi frase secreta de acceso").isEmpty())
    }
}
