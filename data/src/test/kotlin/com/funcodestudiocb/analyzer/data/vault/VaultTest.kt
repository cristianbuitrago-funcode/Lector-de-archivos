package com.funcodestudiocb.analyzer.data.vault

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Parámetros bajos solo para que las pruebas sean rápidas; la app usa [KdfProfile.DEFAULT]. */
    private val fastKdf = KdfProfile(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private var now = 1_000_000L
    private var wiped = 0

    private class FakeDevice : DeviceKeyProtector {
        var key: ByteArray? = ByteArray(32).also(SecureRandom()::nextBytes)
        override fun protect(plaintext: ByteArray) = Aead.seal(key!!, plaintext, byteArrayOf())
        override fun unprotect(blob: ByteArray): ByteArray {
            val k = key ?: throw DeviceKeyUnavailableException("sin clave")
            return try {
                Aead.open(k, blob, byteArrayOf())
            } catch (e: javax.crypto.AEADBadTagException) {
                throw DeviceKeyUnavailableException("otra clave", e)
            }
        }
        override fun destroy() { key = null }
    }

    /** Simula el Keystore biométrico con una clave AES en memoria (sin autenticación real). */
    private class FakeBiometric : BiometricKeyProvider {
        var key: SecretKey? = null
        var invalidated = false
        override fun isAvailable() = true
        override fun newEncryptCipher(): Cipher {
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }
        override fun decryptCipher(iv: ByteArray): Cipher {
            if (invalidated || key == null) throw BiometricKeyInvalidatedException()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        }
        override fun deleteKey() { key = null }
    }

    private val device = FakeDevice()
    private val biometric = FakeBiometric()
    private val file by lazy { File(temp.root, "vault/vault.json") }

    private fun vault() = Vault(VaultStorage(file), device, biometric, fastKdf, { now }, SecureRandom()) { wiped++ }

    private fun pw(s: String) = s.toCharArray()

    @Test
    fun `configurar y desbloquear devuelve la misma clave`() {
        val v = vault()
        assertFalse(v.isConfigured())
        val key = v.setup(pw("Funcode-2026"))
        assertEquals(32, key.size)
        assertTrue(v.isConfigured())

        val result = assertIs<UnlockResult.Success>(vault().unlockWithPassword(pw("Funcode-2026")))
        assertContentEquals(key, result.databaseKey)
    }

    @Test
    fun `el archivo de la boveda no contiene la clave ni la contrasena`() {
        val key = vault().setup(pw("Funcode-2026"))
        val content = file.readText()
        assertFalse(content.contains("Funcode-2026"))
        assertFalse(content.contains(Base64Codec.encode(key)))
    }

    @Test
    fun `no se puede configurar dos veces ni con una contrasena debil`() {
        assertFailsWith<IllegalArgumentException> { vault().setup(pw("1234")) }
        vault().setup(pw("Funcode-2026"))
        assertFailsWith<IllegalStateException> { vault().setup(pw("Otra-clave-99")) }
    }

    @Test
    fun `contrasena incorrecta, espera creciente y reinicio del contador`() {
        val v = vault()
        v.setup(pw("Funcode-2026"))
        repeat(Vault.FREE_ATTEMPTS) { i ->
            val r = assertIs<UnlockResult.WrongPassword>(v.unlockWithPassword(pw("mala-$i")))
            assertEquals(0, r.lockoutUntilEpochMs)
        }
        val fifth = assertIs<UnlockResult.WrongPassword>(v.unlockWithPassword(pw("mala-5")))
        assertEquals(now + 30_000, fifth.lockoutUntilEpochMs)

        // Durante la espera ni siquiera la contraseña correcta se comprueba.
        assertIs<UnlockResult.LockedOut>(v.unlockWithPassword(pw("Funcode-2026")))

        now += 30_001
        assertIs<UnlockResult.Success>(v.unlockWithPassword(pw("Funcode-2026")))
        val again = assertIs<UnlockResult.WrongPassword>(v.unlockWithPassword(pw("mala")))
        assertEquals(1, again.failedAttempts)
    }

    @Test
    fun `progresion de la espera`() {
        assertEquals(0, Vault.lockoutDelayMillis(4))
        assertEquals(30_000, Vault.lockoutDelayMillis(5))
        assertEquals(60_000, Vault.lockoutDelayMillis(6))
        assertEquals(120_000, Vault.lockoutDelayMillis(7))
        assertEquals(15 * 60_000, Vault.lockoutDelayMillis(50))
    }

    @Test
    fun `borrado automatico tras el maximo de intentos si esta activado`() {
        val v = vault()
        v.setup(pw("Funcode-2026"))
        v.updateSettings { it.copy(wipeAfterFailedAttempts = true) }
        var last: UnlockResult? = null
        repeat(Vault.MAX_FAILED_BEFORE_WIPE) {
            now += 60 * 60_000 // salta cualquier espera
            last = v.unlockWithPassword(pw("mala"))
        }
        assertIs<UnlockResult.Wiped>(last)
        assertFalse(v.isConfigured())
        assertNull(device.key)
        assertEquals(1, wiped)
    }

    @Test
    fun `sin la clave del dispositivo los datos son irrecuperables`() {
        val v = vault()
        v.setup(pw("Funcode-2026"))
        device.key = ByteArray(32).also(SecureRandom()::nextBytes) // p. ej. restaurado en otro teléfono
        assertIs<UnlockResult.DeviceKeyLost>(v.unlockWithPassword(pw("Funcode-2026")))
    }

    @Test
    fun `una boveda manipulada o danada se detecta`() {
        vault().setup(pw("Funcode-2026"))
        file.writeText("{ esto no es una bóveda")
        assertIs<UnlockResult.Corrupted>(vault().unlockWithPassword(pw("Funcode-2026")))
    }

    @Test
    fun `cambiar la contrasena conserva la clave de la base de datos`() {
        val v = vault()
        val key = v.setup(pw("Funcode-2026"))
        assertIs<UnlockResult.WrongPassword>(v.changePassword(pw("mala"), pw("Nueva-clave-77")))
        assertIs<UnlockResult.Success>(v.changePassword(pw("Funcode-2026"), pw("Nueva-clave-77")))
        assertIs<UnlockResult.WrongPassword>(v.unlockWithPassword(pw("Funcode-2026")))
        val result = assertIs<UnlockResult.Success>(v.unlockWithPassword(pw("Nueva-clave-77")))
        assertContentEquals(key, result.databaseKey)
    }

    @Test
    fun `biometria activar, desbloquear e invalidacion`() {
        val v = vault()
        val key = v.setup(pw("Funcode-2026"))
        assertIs<BiometricCipherResult.NotEnabled>(v.biometricUnlockCipher())

        v.completeBiometricEnrollment(v.biometricEnrollCipher(), key)
        assertTrue(v.isBiometricEnabled())
        val ready = assertIs<BiometricCipherResult.Ready>(v.biometricUnlockCipher())
        val unlocked = assertIs<UnlockResult.Success>(v.completeBiometricUnlock(ready.cipher))
        assertContentEquals(key, unlocked.databaseKey)

        biometric.invalidated = true // p. ej. se registró una huella nueva
        assertIs<BiometricCipherResult.Invalidated>(v.biometricUnlockCipher())
        assertFalse(v.isBiometricEnabled())
        // La contraseña sigue funcionando.
        assertIs<UnlockResult.Success>(v.unlockWithPassword(pw("Funcode-2026")))
    }

    @Test
    fun `borrado manual elimina todo`() {
        val v = vault()
        v.setup(pw("Funcode-2026"))
        v.wipe()
        assertFalse(v.isConfigured())
        assertFalse(file.exists())
        assertEquals(1, wiped)
    }
}
