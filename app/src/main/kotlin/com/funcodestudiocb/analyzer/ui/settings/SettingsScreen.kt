package com.funcodestudiocb.analyzer.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.BuildConfig
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.data.vault.SecuritySettings
import com.funcodestudiocb.analyzer.security.authenticateWithBiometrics
import com.funcodestudiocb.analyzer.ui.access.formatRemaining
import com.funcodestudiocb.analyzer.ui.access.passwordIssueText
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.PasswordField
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, vulnDb: VulnDbSettingsViewModel, onOpenDiagnostics: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()

    state.message?.let { message ->
        val text = stringResource(
            when (message) {
                SettingsMessage.PasswordChanged -> R.string.msg_password_changed
                SettingsMessage.BiometricEnabled -> R.string.msg_biometric_enabled
                SettingsMessage.BiometricDisabled -> R.string.msg_biometric_disabled
                SettingsMessage.BiometricCancelled -> R.string.msg_biometric_cancelled
            },
        )
        LaunchedEffect(message) {
            snackbar.showSnackbar(text)
            viewModel.messageShown()
        }
    }

    val enrollTitle = stringResource(R.string.app_name)
    val enrollSubtitle = stringResource(R.string.biometric_prompt_subtitle_enroll)
    val negative = stringResource(R.string.biometric_prompt_negative)
    LaunchedEffect(state.pendingEnrollment) {
        val cipher = state.pendingEnrollment ?: return@LaunchedEffect
        val host = activity
        if (host == null) {
            viewModel.onEnrollmentResult(null)
        } else {
            scope.launch { viewModel.onEnrollmentResult(host.authenticateWithBiometrics(cipher, enrollTitle, enrollSubtitle, negative)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FuncodePanel {
                SectionTitle(stringResource(R.string.settings_section_access))
                SettingRow(
                    title = stringResource(R.string.settings_biometric),
                    subtitle = if (state.biometricAvailable) null else stringResource(R.string.settings_biometric_unavailable),
                    trailing = {
                        Switch(
                            checked = state.biometricEnabled,
                            onCheckedChange = viewModel::onBiometricToggle,
                            enabled = state.biometricAvailable || state.biometricEnabled,
                            modifier = Modifier.labelled(stringResource(R.string.settings_biometric)),
                        )
                    },
                )
                SettingRow(
                    title = stringResource(R.string.settings_change_password),
                    onClick = viewModel::requestChangePassword,
                    modifier = Modifier.testTag("settings_change_password"),
                )
                Text(stringResource(R.string.settings_background_lock), style = MaterialTheme.typography.bodyLarge)
                OptionChips(
                    options = SecuritySettings.BACKGROUND_OPTIONS,
                    selected = state.settings.backgroundLockSeconds,
                    label = { seconds ->
                        when {
                            seconds == 0 -> stringResource(R.string.settings_lock_immediate)
                            seconds < 60 -> stringResource(R.string.settings_after_seconds, seconds)
                            else -> stringResource(R.string.settings_after_minutes, seconds / 60)
                        }
                    },
                    onSelect = viewModel::setBackgroundLock,
                )
                Text(stringResource(R.string.settings_inactivity_lock), style = MaterialTheme.typography.bodyLarge)
                OptionChips(
                    options = SecuritySettings.INACTIVITY_OPTIONS,
                    selected = state.settings.inactivityLockMinutes,
                    label = { stringResource(R.string.settings_after_minutes, it) },
                    onSelect = viewModel::setInactivityLock,
                )
                SettingRow(
                    title = stringResource(R.string.settings_wipe_after_failures),
                    subtitle = stringResource(R.string.settings_wipe_after_failures_desc),
                    trailing = {
                        Switch(
                            checked = state.settings.wipeAfterFailedAttempts,
                            onCheckedChange = viewModel::setWipeAfterFailures,
                            modifier = Modifier.labelled(stringResource(R.string.settings_wipe_after_failures)),
                        )
                    },
                )
                SettingRow(title = stringResource(R.string.settings_lock_now), onClick = viewModel::lockNow)
            }

            FuncodePanel(accent = FuncodeColors.Electric) {
                SectionTitle(stringResource(R.string.settings_section_analysis))
                SettingRow(
                    title = stringResource(R.string.settings_keep_copy),
                    subtitle = stringResource(R.string.settings_keep_copy_desc),
                    trailing = {
                        Switch(
                            checked = state.keepCodeCopy,
                            onCheckedChange = viewModel::setKeepCodeCopy,
                            modifier = Modifier.testTag("settings_keep_copy").labelled(stringResource(R.string.settings_keep_copy)),
                        )
                    },
                )
            }

            VulnDbSection(vulnDb)

            FuncodePanel(accent = FuncodeColors.Electric) {
                SectionTitle(stringResource(R.string.settings_section_isolation))
                SettingRow(
                    title = stringResource(R.string.settings_diagnostics),
                    subtitle = stringResource(R.string.settings_diagnostics_desc),
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.testTag("settings_diagnostics"),
                )
            }

            FuncodePanel(accent = MaterialTheme.colorScheme.error) {
                SectionTitle(stringResource(R.string.settings_section_data))
                SettingRow(
                    title = stringResource(R.string.settings_wipe_all),
                    subtitle = stringResource(R.string.settings_wipe_all_desc),
                    onClick = viewModel::requestWipeAll,
                    modifier = Modifier.testTag("settings_wipe_all"),
                )
            }

            FuncodePanel(accent = FuncodeColors.Gold) {
                SectionTitle(stringResource(R.string.settings_section_about))
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.settings_about_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                var showLicenses by remember { mutableStateOf(false) }
                SettingRow(title = stringResource(R.string.settings_third_party), onClick = { showLicenses = !showLicenses })
                if (showLicenses) {
                    Text(stringResource(R.string.settings_third_party_text), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_unrar_license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (val dialog = state.dialog) {
        is SettingsDialog.ConfirmPassword -> ConfirmPasswordDialog(dialog, viewModel)
        is SettingsDialog.ChangePassword -> ChangePasswordDialog(dialog, viewModel)
        null -> Unit
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun OptionChips(options: List<Int>, selected: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun dialogErrorText(error: DialogError?): String? = when (error) {
    DialogError.WrongPassword -> stringResource(R.string.msg_wrong_password)
    is DialogError.LockedOut -> stringResource(R.string.unlock_locked_out, formatRemaining(error.untilEpochMs - System.currentTimeMillis()))
    DialogError.Failed -> stringResource(R.string.unlock_corrupted)
    null -> null
}

@Composable
private fun ConfirmPasswordDialog(dialog: SettingsDialog.ConfirmPassword, viewModel: SettingsViewModel) {
    var password by remember { mutableStateOf("") }
    val wipe = dialog.purpose == PasswordPurpose.WipeAll
    AlertDialog(
        onDismissRequest = { if (!dialog.working) viewModel.dismissDialog() },
        title = { Text(stringResource(if (wipe) R.string.dialog_wipe_title else R.string.dialog_confirm_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (wipe) Text(stringResource(R.string.dialog_wipe_text))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.field_password),
                    enabled = !dialog.working,
                    isError = dialog.error != null,
                    supportingText = dialogErrorText(dialog.error),
                    modifier = Modifier.testTag("confirm_password"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmPassword(password) }, enabled = !dialog.working && password.isNotEmpty()) {
                Text(
                    stringResource(if (wipe) R.string.action_wipe else R.string.action_confirm),
                    color = if (wipe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDialog, enabled = !dialog.working) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ChangePasswordDialog(dialog: SettingsDialog.ChangePassword, viewModel: SettingsViewModel) {
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!dialog.working) viewModel.dismissDialog() },
        title = { Text(stringResource(R.string.dialog_change_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(
                    value = current,
                    onValueChange = { current = it },
                    label = stringResource(R.string.field_current_password),
                    imeAction = ImeAction.Next,
                    enabled = !dialog.working,
                    isError = dialog.error != null,
                    supportingText = dialogErrorText(dialog.error),
                )
                PasswordField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        viewModel.onNewPasswordInput(newPassword, confirm)
                    },
                    label = stringResource(R.string.field_new_password),
                    imeAction = ImeAction.Next,
                    enabled = !dialog.working,
                    isError = newPassword.isNotEmpty() && dialog.issues.isNotEmpty(),
                    supportingText = dialog.issues.firstOrNull()?.takeIf { newPassword.isNotEmpty() }?.let { passwordIssueText(it) },
                )
                PasswordField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        viewModel.onNewPasswordInput(newPassword, confirm)
                    },
                    label = stringResource(R.string.field_password_confirm),
                    enabled = !dialog.working,
                    isError = dialog.mismatch,
                    supportingText = if (dialog.mismatch) stringResource(R.string.issue_mismatch) else null,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.changePassword(current, newPassword, confirm) },
                enabled = !dialog.working && current.isNotEmpty() && newPassword.isNotEmpty(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDialog, enabled = !dialog.working) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Nombre para los lectores de pantalla de un control cuyo texto está al lado (un interruptor en una fila). */
internal fun Modifier.labelled(label: String): Modifier = semantics { contentDescription = label }
