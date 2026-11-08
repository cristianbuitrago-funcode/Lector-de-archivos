package com.funcodestudiocb.analyzer.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Muestra el diálogo biométrico del sistema ligado a un [Cipher] del Keystore (CryptoObject).
 * Devuelve el cifrador ya autenticado, o `null` si se canceló o falló.
 */
suspend fun FragmentActivity.authenticateWithBiometrics(
    cipher: Cipher,
    title: String,
    subtitle: String,
    negativeText: String,
): Cipher? = suspendCancellableCoroutine { continuation ->
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(null)
            }
            // onAuthenticationFailed: el sistema permite reintentar; no se hace nada.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negativeText)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
}
