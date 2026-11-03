package com.funcodestudiocb.analyzer.data.vault

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/** Ajustes de seguridad del propietario. No son secretos; se guardan junto a la bóveda. */
@Serializable
data class SecuritySettings(
    /** Segundos fuera de la app tras los que se bloquea (0 = inmediatamente). */
    val backgroundLockSeconds: Int = 60,
    /** Minutos sin interacción tras los que se bloquea. */
    val inactivityLockMinutes: Int = 5,
    /** Borrar todos los datos tras [Vault.MAX_FAILED_BEFORE_WIPE] contraseñas incorrectas seguidas. */
    val wipeAfterFailedAttempts: Boolean = false,
) {
    companion object {
        val BACKGROUND_OPTIONS = listOf(0, 30, 60, 300)
        val INACTIVITY_OPTIONS = listOf(1, 5, 15)
    }
}

@Serializable
data class BiometricSlot(val iv: String, val ciphertext: String)

/** Contenido del archivo de la bóveda. Nunca contiene la clave de la base de datos en claro. */
@Serializable
data class VaultFile(
    val version: Int = VERSION,
    val kdf: KdfParams,
    /** `protectorDispositivo( AES-GCM( Argon2id(contraseña), claveBD ) )`, en Base64. */
    val passwordSlot: String,
    /** Clave de la BD cifrada con la clave biométrica del Keystore, si la biometría está activada. */
    val biometricSlot: BiometricSlot? = null,
    val failedAttempts: Int = 0,
    val lockoutUntilEpochMs: Long = 0,
    val settings: SecuritySettings = SecuritySettings(),
) {
    companion object {
        const val VERSION = 1
    }
}

class VaultCorruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lectura y escritura atómica del archivo de la bóveda. */
class VaultStorage(private val file: File) {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun exists(): Boolean = file.exists()

    fun read(): VaultFile? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            throw VaultCorruptedException("No se pudo leer la bóveda", e)
        }
        val vault = try {
            json.decodeFromString(VaultFile.serializer(), text)
        } catch (e: SerializationException) {
            throw VaultCorruptedException("La bóveda está dañada", e)
        } catch (e: IllegalArgumentException) {
            throw VaultCorruptedException("La bóveda está dañada", e)
        }
        if (vault.version != VaultFile.VERSION) throw VaultCorruptedException("Versión de bóveda no admitida")
        return vault
    }

    fun write(vault: VaultFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(VaultFile.serializer(), vault).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("No se pudo guardar la bóveda")
        }
    }

    fun delete() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}

sealed interface UnlockResult {
    /** La clave de la BD; quien la recibe debe borrarla (`fill(0)`) en cuanto la use. */
    class Success(val databaseKey: ByteArray) : UnlockResult

    data class WrongPassword(
        val failedAttempts: Int,
        val lockoutUntilEpochMs: Long,
        /** Intentos restantes antes del borrado automático, o `null` si está desactivado. */
        val attemptsBeforeWipe: Int?,
    ) : UnlockResult

    data class LockedOut(val untilEpochMs: Long) : UnlockResult

    /** Se superó el máximo de intentos con el borrado automático activado: todos los datos se borraron. */
    data object Wiped : UnlockResult

    /** La clave del dispositivo ya no existe (p. ej. restauración en otro teléfono): los datos son irrecuperables. */
    data object DeviceKeyLost : UnlockResult

    data object Corrupted : UnlockResult
    data object NotConfigured : UnlockResult

    /** La biometría falló o fue invalidada; se debe usar la contraseña. */
    data object BiometricFailed : UnlockResult
}

sealed interface BiometricCipherResult {
    class Ready(val cipher: Cipher) : BiometricCipherResult
    data object NotEnabled : BiometricCipherResult

    /** La biometría cambió (huella nueva, etc.): se desactivó y hay que volver a activarla tras entrar con contraseña. */
    data object Invalidated : BiometricCipherResult
}

/**
 * Bóveda de acceso exclusivo del propietario.
 *
 * Esquema de claves:
 * - La base de datos se cifra con una clave aleatoria de 256 bits (`claveBD`).
 * - `claveBD` se guarda cifrada con AES-256-GCM bajo una clave derivada de la contraseña con Argon2id,
 *   y ese resultado se cifra otra vez con una clave del Android Keystore que no puede salir del teléfono.
 *   Así, ni copiando los archivos ni conociendo solo la contraseña se pueden descifrar los datos fuera
 *   de este dispositivo.
 * - Si la biometría está activada, `claveBD` se guarda además cifrada con una clave del Keystore que solo
 *   puede usarse tras autenticarse con biometría fuerte, y que el sistema invalida si cambia la biometría.
 * - La contraseña no se guarda: se verifica porque el cifrado autenticado solo se abre con la clave correcta.
 */
class Vault(
    private val storage: VaultStorage,
    private val device: DeviceKeyProtector,
    private val biometric: BiometricKeyProvider?,
    private val kdfProfile: KdfProfile = KdfProfile.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** Borra el resto de datos de la app (BD, temporales). Se invoca en cada borrado. */
    private val onWipe: () -> Unit = {},
) {
    private val lock = Any()

    fun isConfigured(): Boolean = synchronized(lock) { storage.exists() }

    fun settings(): SecuritySettings = synchronized(lock) {
        runCatching { storage.read()?.settings }.getOrNull() ?: SecuritySettings()
    }

    fun updateSettings(transform: (SecuritySettings) -> SecuritySettings) = synchronized(lock) {
        val current = storage.read() ?: return@synchronized
        storage.write(current.copy(settings = transform(current.settings)))
    }

    /** Configura el acceso por primera vez. Devuelve la clave nueva de la BD. */
    fun setup(password: CharArray): ByteArray = synchronized(lock) {
        check(!storage.exists()) { "El acceso ya está configurado" }
        require(PasswordPolicy.check(password).isEmpty()) { "La contraseña no cumple la política" }
        val databaseKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        val params = PasswordKdf.newParams(kdfProfile, random)
        storage.write(VaultFile(kdf = params, passwordSlot = sealPasswordSlot(password, params, databaseKey)))
        databaseKey
    }

    fun lockoutRemainingMillis(): Long = synchronized(lock) {
        val file = runCatching { storage.read() }.getOrNull() ?: return@synchronized 0
        (file.lockoutUntilEpochMs - clock()).coerceAtLeast(0)
    }

    fun unlockWithPassword(password: CharArray): UnlockResult = synchronized(lock) {
        val file = readOrResult { return@synchronized it }
        val now = clock()
        if (now < file.lockoutUntilEpochMs) return@synchronized UnlockResult.LockedOut(file.lockoutUntilEpochMs)

        when (val opened = openPasswordSlot(file, password)) {
            is SlotOpen.Key -> {
                if (file.failedAttempts != 0 || file.lockoutUntilEpochMs != 0L) {
                    storage.write(file.copy(failedAttempts = 0, lockoutUntilEpochMs = 0))
                }
                UnlockResult.Success(opened.key)
            }
            SlotOpen.WrongKey -> registerFailure(file, now)
            SlotOpen.DeviceKeyLost -> UnlockResult.DeviceKeyLost
        }
    }

    fun changePassword(current: CharArray, newPassword: CharArray): UnlockResult = synchronized(lock) {
        require(PasswordPolicy.check(newPassword).isEmpty()) { "La contraseña nueva no cumple la política" }
        val result = unlockWithPassword(current)
        if (result !is UnlockResult.Success) return@synchronized result
        val file = storage.read() ?: return@synchronized UnlockResult.NotConfigured
        val params = PasswordKdf.newParams(kdfProfile, random)
        storage.write(file.copy(kdf = params, passwordSlot = sealPasswordSlot(newPassword, params, result.databaseKey)))
        result
    }

    fun isBiometricAvailable(): Boolean = biometric?.isAvailable() == true

    fun isBiometricEnabled(): Boolean = synchronized(lock) {
        runCatching { storage.read()?.biometricSlot != null }.getOrDefault(false)
    }

    /** Primer paso para activar la biometría: el cifrador debe autenticarse con BiometricPrompt. */
    fun biometricEnrollCipher(): Cipher {
        val provider = checkNotNull(biometric) { "Biometría no disponible" }
        return provider.newEncryptCipher()
    }

    /** Segundo paso: con el cifrador ya autenticado, guarda la clave de la BD protegida por la biometría. */
    fun completeBiometricEnrollment(authenticatedCipher: Cipher, databaseKey: ByteArray) = synchronized(lock) {
        val file = storage.read() ?: return@synchronized
        val ciphertext = authenticatedCipher.doFinal(databaseKey)
        val slot = BiometricSlot(Base64Codec.encode(authenticatedCipher.iv), Base64Codec.encode(ciphertext))
        storage.write(file.copy(biometricSlot = slot))
    }

    fun biometricUnlockCipher(): BiometricCipherResult = synchronized(lock) {
        val provider = biometric ?: return@synchronized BiometricCipherResult.NotEnabled
        val file = runCatching { storage.read() }.getOrNull() ?: return@synchronized BiometricCipherResult.NotEnabled
        val slot = file.biometricSlot ?: return@synchronized BiometricCipherResult.NotEnabled
        try {
            BiometricCipherResult.Ready(provider.decryptCipher(Base64Codec.decode(slot.iv)))
        } catch (e: BiometricKeyInvalidatedException) {
            provider.deleteKey()
            storage.write(file.copy(biometricSlot = null))
            BiometricCipherResult.Invalidated
        }
    }

    fun completeBiometricUnlock(authenticatedCipher: Cipher): UnlockResult = synchronized(lock) {
        val file = readOrResult { return@synchronized it }
        val slot = file.biometricSlot ?: return@synchronized UnlockResult.BiometricFailed
        try {
            val key = authenticatedCipher.doFinal(Base64Codec.decode(slot.ciphertext))
            if (file.failedAttempts != 0 || file.lockoutUntilEpochMs != 0L) {
                storage.write(file.copy(failedAttempts = 0, lockoutUntilEpochMs = 0))
            }
            UnlockResult.Success(key)
        } catch (e: GeneralSecurityException) {
            UnlockResult.BiometricFailed
        } catch (e: IllegalStateException) {
            UnlockResult.BiometricFailed
        }
    }

    fun disableBiometric() = synchronized(lock) {
        biometric?.deleteKey()
        val file = runCatching { storage.read() }.getOrNull() ?: return@synchronized
        if (file.biometricSlot != null) storage.write(file.copy(biometricSlot = null))
    }

    /** Borra la bóveda, las claves del Keystore y (mediante [onWipe]) el resto de datos de la app. */
    fun wipe() = synchronized(lock) {
        storage.delete()
        runCatching { device.destroy() }
        runCatching { biometric?.deleteKey() }
        onWipe()
    }

    private inline fun readOrResult(onError: (UnlockResult) -> Nothing): VaultFile =
        try {
            storage.read() ?: onError(UnlockResult.NotConfigured)
        } catch (e: VaultCorruptedException) {
            onError(UnlockResult.Corrupted)
        }

    private fun registerFailure(file: VaultFile, now: Long): UnlockResult {
        val failures = file.failedAttempts + 1
        if (file.settings.wipeAfterFailedAttempts && failures >= MAX_FAILED_BEFORE_WIPE) {
            wipe()
            return UnlockResult.Wiped
        }
        val until = lockoutDelayMillis(failures).let { if (it > 0) now + it else 0L }
        storage.write(file.copy(failedAttempts = failures, lockoutUntilEpochMs = until))
        val before = if (file.settings.wipeAfterFailedAttempts) MAX_FAILED_BEFORE_WIPE - failures else null
        return UnlockResult.WrongPassword(failures, until, before)
    }

    private fun sealPasswordSlot(password: CharArray, params: KdfParams, databaseKey: ByteArray): String {
        val kek = PasswordKdf.derive(password, params)
        try {
            val sealed = Aead.seal(kek, databaseKey, AAD_PASSWORD_SLOT, random)
            return Base64Codec.encode(device.protect(sealed))
        } finally {
            kek.fill(0)
        }
    }

    private sealed interface SlotOpen {
        class Key(val key: ByteArray) : SlotOpen
        data object WrongKey : SlotOpen
        data object DeviceKeyLost : SlotOpen
    }

    private fun openPasswordSlot(file: VaultFile, password: CharArray): SlotOpen {
        val sealed = try {
            device.unprotect(Base64Codec.decode(file.passwordSlot))
        } catch (e: DeviceKeyUnavailableException) {
            return SlotOpen.DeviceKeyLost
        }
        val kek = PasswordKdf.derive(password, file.kdf)
        return try {
            SlotOpen.Key(Aead.open(kek, sealed, AAD_PASSWORD_SLOT))
        } catch (e: AEADBadTagException) {
            SlotOpen.WrongKey
        } finally {
            kek.fill(0)
        }
    }

    companion object {
        const val KEY_BYTES = 32
        const val MAX_FAILED_BEFORE_WIPE = 10
        const val FREE_ATTEMPTS = 4
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 15 * 60_000L
        private val AAD_PASSWORD_SLOT = "funcode-vault-v1:password-slot".toByteArray()

        /** Espera tras [failures] fallos seguidos: 4 intentos libres, después 30 s, 60 s, 2 min… hasta 15 min. */
        fun lockoutDelayMillis(failures: Int): Long {
            if (failures <= FREE_ATTEMPTS) return 0
            val shift = (failures - FREE_ATTEMPTS - 1).coerceAtMost(10)
            return (BASE_LOCKOUT_MS shl shift).coerceAtMost(MAX_LOCKOUT_MS)
        }
    }
}
