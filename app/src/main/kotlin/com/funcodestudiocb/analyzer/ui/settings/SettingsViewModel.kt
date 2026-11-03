package com.funcodestudiocb.analyzer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.data.vault.PasswordIssue
import com.funcodestudiocb.analyzer.data.vault.PasswordPolicy
import com.funcodestudiocb.analyzer.data.vault.SecuritySettings
import com.funcodestudiocb.analyzer.data.vault.UnlockResult
import com.funcodestudiocb.analyzer.ui.access.withPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

/** Para qué se pide la contraseña en el diálogo de confirmación. */
enum class PasswordPurpose { EnableBiometric, WipeAll }

sealed interface SettingsDialog {
    data class ConfirmPassword(val purpose: PasswordPurpose, val error: DialogError? = null, val working: Boolean = false) : SettingsDialog
    data class ChangePassword(
        val issues: Set<PasswordIssue> = emptySet(),
        val mismatch: Boolean = false,
        val error: DialogError? = null,
        val working: Boolean = false,
    ) : SettingsDialog
}

sealed interface DialogError {
    data object WrongPassword : DialogError
    data class LockedOut(val untilEpochMs: Long) : DialogError
    data object Failed : DialogError
}

enum class SettingsMessage { PasswordChanged, BiometricEnabled, BiometricDisabled, BiometricCancelled }

data class SettingsUiState(
    val settings: SecuritySettings,
    val biometricAvailable: Boolean,
    val biometricEnabled: Boolean,
    val dialog: SettingsDialog? = null,
    val message: SettingsMessage? = null,
    /** Cifrador listo para que la interfaz muestre el diálogo biométrico de activación. */
    val pendingEnrollment: Cipher? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val vault = container.vault
    private var enrollmentKey: ByteArray? = null

    private val _state = MutableStateFlow(
        SettingsUiState(vault.settings(), vault.isBiometricAvailable(), vault.isBiometricEnabled()),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun messageShown() = _state.update { it.copy(message = null) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun onBiometricToggle(enable: Boolean) {
        if (enable) {
            _state.update { it.copy(dialog = SettingsDialog.ConfirmPassword(PasswordPurpose.EnableBiometric)) }
        } else {
            vault.disableBiometric()
            _state.update { it.copy(biometricEnabled = false, message = SettingsMessage.BiometricDisabled) }
        }
    }

    fun requestWipeAll() = _state.update { it.copy(dialog = SettingsDialog.ConfirmPassword(PasswordPurpose.WipeAll)) }

    fun requestChangePassword() = _state.update { it.copy(dialog = SettingsDialog.ChangePassword()) }

    fun confirmPassword(password: String) {
        val dialog = _state.value.dialog as? SettingsDialog.ConfirmPassword ?: return
        if (dialog.working) return
        _state.update { it.copy(dialog = dialog.copy(working = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { withPassword(password) { vault.unlockWithPassword(it) } }
            if (result !is UnlockResult.Success) {
                _state.update { it.copy(dialog = dialog.copy(working = false, error = errorFor(result))) }
                return@launch
            }
            when (dialog.purpose) {
                PasswordPurpose.WipeAll -> {
                    result.databaseKey.fill(0)
                    _state.update { it.copy(dialog = null) }
                    vault.wipe()
                }
                PasswordPurpose.EnableBiometric -> {
                    val cipher = runCatching { vault.biometricEnrollCipher() }.getOrNull()
                    if (cipher == null) {
                        result.databaseKey.fill(0)
                        _state.update { it.copy(dialog = null, message = SettingsMessage.BiometricCancelled) }
                    } else {
                        enrollmentKey = result.databaseKey
                        _state.update { it.copy(dialog = null, pendingEnrollment = cipher) }
                    }
                }
            }
        }
    }

    /** Resultado del diálogo biométrico: cifrador autenticado o `null` si se canceló. */
    fun onEnrollmentResult(authenticated: Cipher?) {
        val key = enrollmentKey
        enrollmentKey = null
        try {
            if (authenticated != null && key != null) {
                vault.completeBiometricEnrollment(authenticated, key)
                _state.update { it.copy(pendingEnrollment = null, biometricEnabled = true, message = SettingsMessage.BiometricEnabled) }
            } else {
                _state.update { it.copy(pendingEnrollment = null, message = SettingsMessage.BiometricCancelled) }
            }
        } finally {
            key?.fill(0)
        }
    }

    fun onNewPasswordInput(newPassword: String, confirm: String) {
        val dialog = _state.value.dialog as? SettingsDialog.ChangePassword ?: return
        _state.update {
            it.copy(
                dialog = dialog.copy(
                    issues = withPassword(newPassword) { p -> PasswordPolicy.check(p) },
                    mismatch = confirm.isNotEmpty() && confirm != newPassword,
                ),
            )
        }
    }

    fun changePassword(current: String, newPassword: String, confirm: String) {
        val dialog = _state.value.dialog as? SettingsDialog.ChangePassword ?: return
        val issues = withPassword(newPassword) { PasswordPolicy.check(it) }
        if (issues.isNotEmpty() || newPassword != confirm || dialog.working) {
            _state.update { it.copy(dialog = dialog.copy(issues = issues, mismatch = newPassword != confirm)) }
            return
        }
        _state.update { it.copy(dialog = dialog.copy(working = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                withPassword(current) { c -> withPassword(newPassword) { n -> vault.changePassword(c, n) } }
            }
            if (result is UnlockResult.Success) {
                result.databaseKey.fill(0)
                _state.update { it.copy(dialog = null, message = SettingsMessage.PasswordChanged) }
            } else {
                _state.update { it.copy(dialog = dialog.copy(working = false, error = errorFor(result))) }
            }
        }
    }

    fun setBackgroundLock(seconds: Int) = updateSettings { it.copy(backgroundLockSeconds = seconds) }

    fun setInactivityLock(minutes: Int) = updateSettings { it.copy(inactivityLockMinutes = minutes) }

    fun setWipeAfterFailures(enabled: Boolean) = updateSettings { it.copy(wipeAfterFailedAttempts = enabled) }

    fun lockNow() = container.session.lock()

    private fun updateSettings(transform: (SecuritySettings) -> SecuritySettings) {
        vault.updateSettings(transform)
        _state.update { it.copy(settings = vault.settings()) }
    }

    private fun errorFor(result: UnlockResult): DialogError = when (result) {
        is UnlockResult.WrongPassword ->
            if (result.lockoutUntilEpochMs > System.currentTimeMillis()) DialogError.LockedOut(result.lockoutUntilEpochMs) else DialogError.WrongPassword
        is UnlockResult.LockedOut -> DialogError.LockedOut(result.untilEpochMs)
        else -> DialogError.Failed
    }

    override fun onCleared() {
        enrollmentKey?.fill(0)
        enrollmentKey = null
    }
}
