package com.funcodestudiocb.analyzer.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.data.session.SessionManager
import com.funcodestudiocb.analyzer.data.vault.BiometricCipherResult
import com.funcodestudiocb.analyzer.data.vault.PasswordIssue
import com.funcodestudiocb.analyzer.data.vault.PasswordPolicy
import com.funcodestudiocb.analyzer.data.vault.UnlockResult
import com.funcodestudiocb.analyzer.data.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/** Ejecuta [block] con la contraseña como `CharArray` y la borra de memoria al terminar. */
internal inline fun <T> withPassword(text: String, block: (CharArray) -> T): T {
    val chars = text.toCharArray()
    try {
        return block(chars)
    } finally {
        chars.fill('\u0000')
    }
}

data class SetupUiState(
    val password: String = "",
    val confirm: String = "",
    val issues: Set<PasswordIssue> = emptySet(),
    val mismatch: Boolean = false,
    val attempted: Boolean = false,
    val working: Boolean = false,
    /** El acceso ya se creó y se ofrece activar la biometría antes de entrar. */
    val offerBiometric: Boolean = false,
)

/** Configuración inicial del acceso del propietario. */
class SetupViewModel(
    private val vault: Vault,
    private val session: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** Clave de la BD retenida solo mientras se decide si se activa la biometría. */
    private var pendingKey: ByteArray? = null

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value).validated() }

    fun onConfirmChange(value: String) = _state.update { it.copy(confirm = value).validated() }

    private fun SetupUiState.validated(): SetupUiState = copy(
        issues = withPassword(password) { PasswordPolicy.check(it) },
        mismatch = confirm.isNotEmpty() && confirm != password,
    )

    fun create() {
        val current = _state.value.copy(attempted = true).validated()
        _state.value = current.copy(mismatch = current.confirm != current.password)
        if (current.issues.isNotEmpty() || current.confirm != current.password || current.working) return

        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val key = withContext(Dispatchers.Default) { withPassword(current.password) { vault.setup(it) } }
            if (vault.isBiometricAvailable()) {
                pendingKey = key
                _state.update { SetupUiState(offerBiometric = true) }
            } else {
                _state.value = SetupUiState()
                session.unlock(key)
            }
        }
    }

    fun biometricEnrollCipher(): Cipher? = runCatching { vault.biometricEnrollCipher() }.getOrNull()

    /** Con el cifrador autenticado (o `null` si se rechazó/canceló), termina y entra en la app. */
    fun finishBiometricOffer(authenticated: Cipher?) {
        val key = pendingKey ?: return
        pendingKey = null
        if (authenticated != null) {
            runCatching { vault.completeBiometricEnrollment(authenticated, key) }
        }
        _state.value = SetupUiState()
        session.unlock(key)
    }

    override fun onCleared() {
        pendingKey?.fill(0)
        pendingKey = null
    }
}

sealed interface UnlockMessage {
    data class Wrong(val failures: Int, val attemptsBeforeWipe: Int?) : UnlockMessage
    data class LockedOut(val untilEpochMs: Long) : UnlockMessage
    data object Wiped : UnlockMessage
    data object DeviceKeyLost : UnlockMessage
    data object Corrupted : UnlockMessage
    data object BiometricInvalidated : UnlockMessage
    data object BiometricFailed : UnlockMessage
}

data class UnlockUiState(
    val password: String = "",
    val working: Boolean = false,
    val message: UnlockMessage? = null,
    val lockoutUntilEpochMs: Long = 0,
    val biometricEnabled: Boolean = false,
) {
    /** Estados de los que solo se sale borrando los datos. */
    val fatal: Boolean get() = message == UnlockMessage.DeviceKeyLost || message == UnlockMessage.Corrupted
}

/** Desbloqueo de la sesión con contraseña o biometría. */
class UnlockViewModel(
    private val vault: Vault,
    private val session: SessionManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    private fun initial(): UnlockUiState {
        val remaining = vault.lockoutRemainingMillis()
        return UnlockUiState(
            biometricEnabled = vault.isBiometricEnabled(),
            lockoutUntilEpochMs = if (remaining > 0) clock() + remaining else 0,
            message = if (remaining > 0) UnlockMessage.LockedOut(clock() + remaining) else null,
        )
    }

    /** Vuelve a leer el estado de la bóveda (p. ej. al volver a la pantalla tras un bloqueo). */
    fun refresh() = _state.update { initial().copy(message = it.message.takeIf { m -> m is UnlockMessage.Wrong }) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun unlock() {
        val current = _state.value
        if (current.working || current.password.isEmpty() || clock() < current.lockoutUntilEpochMs) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                withPassword(current.password) { vault.unlockWithPassword(it) }
            }
            handle(result)
        }
    }

    fun biometricCipher(): Cipher? = when (val r = vault.biometricUnlockCipher()) {
        is BiometricCipherResult.Ready -> r.cipher
        BiometricCipherResult.Invalidated -> {
            _state.update { it.copy(biometricEnabled = false, message = UnlockMessage.BiometricInvalidated) }
            null
        }
        BiometricCipherResult.NotEnabled -> {
            _state.update { it.copy(biometricEnabled = false) }
            null
        }
    }

    fun onBiometricResult(authenticated: Cipher?) {
        if (authenticated == null) return // cancelado: se sigue con la contraseña
        handle(vault.completeBiometricUnlock(authenticated))
    }

    fun wipeEverything() {
        vault.wipe()
        _state.value = UnlockUiState()
    }

    private fun handle(result: UnlockResult) {
        when (result) {
            is UnlockResult.Success -> {
                _state.value = UnlockUiState(biometricEnabled = vault.isBiometricEnabled())
                session.unlock(result.databaseKey)
            }
            is UnlockResult.WrongPassword -> _state.update {
                it.copy(
                    working = false,
                    password = "",
                    lockoutUntilEpochMs = result.lockoutUntilEpochMs,
                    message = if (result.lockoutUntilEpochMs > clock()) {
                        UnlockMessage.LockedOut(result.lockoutUntilEpochMs)
                    } else {
                        UnlockMessage.Wrong(result.failedAttempts, result.attemptsBeforeWipe)
                    },
                )
            }
            is UnlockResult.LockedOut -> _state.update {
                it.copy(working = false, lockoutUntilEpochMs = result.untilEpochMs, message = UnlockMessage.LockedOut(result.untilEpochMs))
            }
            UnlockResult.Wiped -> {
                _state.value = UnlockUiState(message = UnlockMessage.Wiped)
                session.reset()
            }
            UnlockResult.DeviceKeyLost -> _state.update { it.copy(working = false, message = UnlockMessage.DeviceKeyLost) }
            UnlockResult.Corrupted -> _state.update { it.copy(working = false, message = UnlockMessage.Corrupted) }
            UnlockResult.NotConfigured -> session.reset()
            UnlockResult.BiometricFailed -> _state.update { it.copy(working = false, message = UnlockMessage.BiometricFailed) }
        }
    }
}
