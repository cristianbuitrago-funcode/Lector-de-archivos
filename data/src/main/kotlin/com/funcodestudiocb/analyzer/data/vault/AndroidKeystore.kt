package com.funcodestudiocb.analyzer.data.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128
private const val IV_BYTES = 12

private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

/**
 * Genera una clave AES-256 en el Keystore, en StrongBox (chip de seguridad dedicado) si el dispositivo
 * lo tiene; si no, en el entorno de ejecución de confianza (TEE).
 */
private fun generateKey(
    context: Context,
    alias: String,
    configure: KeyGenParameterSpec.Builder.() -> Unit,
): SecretKey {
    fun build(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply(configure)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (hasStrongBox) {
        try {
            return build(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            // Algunos dispositivos anuncian StrongBox pero no admiten esta configuración: se usa el TEE.
        }
    }
    return build(strongBox = false)
}

/** Clave del dispositivo, sin autenticación por uso: impide descifrar la bóveda fuera de este teléfono. */
class AndroidKeystoreDeviceProtector(
    private val context: Context,
    private val alias: String = "funcode.device.v1",
) : DeviceKeyProtector {

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return if (create) generateKey(context, alias) {} else null
    }

    override fun protect(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun unprotect(blob: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw DeviceKeyUnavailableException("Datos protegidos inválidos")
        val key = key(create = false) ?: throw DeviceKeyUnavailableException("La clave del dispositivo no existe")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyUnavailableException("No se pudo usar la clave del dispositivo", e)
        }
    }

    override fun destroy() {
        runCatching { keyStore().deleteEntry(alias) }
    }
}

/** Clave ligada a la biometría fuerte del propietario; se invalida si cambia la biometría del dispositivo. */
class AndroidBiometricKeyProvider(
    private val context: Context,
    private val alias: String = "funcode.biometric.v1",
) : BiometricKeyProvider {

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override fun newEncryptCipher(): Cipher {
        deleteKey()
        val key = generateKey(context, alias) {
            setUserAuthenticationRequired(true)
            setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Autenticación en cada uso, solo con biometría fuerte.
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
        }
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: throw BiometricKeyInvalidatedException()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricKeyInvalidatedException(e)
        }
    }

    override fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }
}
