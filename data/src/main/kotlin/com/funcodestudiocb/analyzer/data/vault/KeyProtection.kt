package com.funcodestudiocb.analyzer.data.vault

import javax.crypto.Cipher

/**
 * Protege datos con una clave que no puede salir del dispositivo (Android Keystore).
 * La bóveda cifra con ella su contenido para que copiar los archivos a otro equipo no sirva de nada.
 */
interface DeviceKeyProtector {
    fun protect(plaintext: ByteArray): ByteArray

    /** Lanza [DeviceKeyUnavailableException] si la clave del dispositivo ya no existe o no sirve. */
    fun unprotect(blob: ByteArray): ByteArray

    fun destroy()
}

class DeviceKeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Claves ligadas a la biometría del propietario (Android Keystore con autenticación obligatoria por uso).
 *
 * Los `Cipher` devueltos deben autenticarse con BiometricPrompt (CryptoObject) antes de usarse.
 */
interface BiometricKeyProvider {
    /** ¿Hay biometría fuerte configurada y disponible en el dispositivo? */
    fun isAvailable(): Boolean

    /** Crea (o recrea) la clave y devuelve un cifrador listo para autenticar y cifrar. */
    fun newEncryptCipher(): Cipher

    /**
     * Devuelve un cifrador listo para autenticar y descifrar.
     * Lanza [BiometricKeyInvalidatedException] si la clave quedó invalidada (p. ej. se añadió una huella nueva).
     */
    fun decryptCipher(iv: ByteArray): Cipher

    fun deleteKey()
}

class BiometricKeyInvalidatedException(cause: Throwable? = null) :
    Exception("La clave biométrica quedó invalidada por un cambio en la biometría del dispositivo", cause)
