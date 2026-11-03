package com.funcodestudiocb.analyzer.ui.access

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.data.vault.PasswordIssue
import com.funcodestudiocb.analyzer.security.authenticateWithBiometrics
import com.funcodestudiocb.analyzer.ui.components.BrandHeader
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.PasswordField
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun AccessLayout(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandHeader()
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun passwordIssueText(issue: PasswordIssue): String = stringResource(
    when (issue) {
        PasswordIssue.TOO_SHORT -> R.string.issue_too_short
        PasswordIssue.TOO_LONG -> R.string.issue_too_long
        PasswordIssue.TOO_SIMPLE -> R.string.issue_too_simple
        PasswordIssue.TRIVIAL -> R.string.issue_trivial
    },
)

@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()

    AccessLayout {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)

        val showIssues = state.attempted || state.password.length >= 4
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.field_password),
            imeAction = ImeAction.Next,
            enabled = !state.working,
            isError = showIssues && state.issues.isNotEmpty(),
            supportingText = if (showIssues && state.issues.isNotEmpty()) {
                passwordIssueText(state.issues.first())
            } else {
                stringResource(R.string.password_hint)
            },
            modifier = Modifier.testTag("setup_password"),
        )
        PasswordField(
            value = state.confirm,
            onValueChange = viewModel::onConfirmChange,
            label = stringResource(R.string.field_password_confirm),
            enabled = !state.working,
            isError = state.mismatch,
            supportingText = if (state.mismatch) stringResource(R.string.issue_mismatch) else null,
            modifier = Modifier.testTag("setup_confirm"),
        )

        FuncodePanel(accent = FuncodeColors.Gold) {
            Text(stringResource(R.string.setup_warning), style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = viewModel::create,
            enabled = !state.working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_create"),
        ) {
            if (state.working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.setup_working))
            } else {
                Text(stringResource(R.string.setup_create))
            }
        }
    }

    if (state.offerBiometric) {
        val title = stringResource(R.string.app_name)
        val subtitle = stringResource(R.string.biometric_prompt_subtitle_enroll)
        val negative = stringResource(R.string.biometric_prompt_negative)
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.Face, contentDescription = null) },
            title = { Text(stringResource(R.string.biometric_offer_title)) },
            text = { Text(stringResource(R.string.biometric_offer_text)) },
            confirmButton = {
                TextButton(onClick = {
                    val cipher = viewModel.biometricEnrollCipher()
                    if (cipher == null || activity == null) {
                        viewModel.finishBiometricOffer(null)
                    } else {
                        scope.launch {
                            viewModel.finishBiometricOffer(activity.authenticateWithBiometrics(cipher, title, subtitle, negative))
                        }
                    }
                }) { Text(stringResource(R.string.action_activate)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.finishBiometricOffer(null) }) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }
}

@Composable
fun UnlockScreen(viewModel: UnlockViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.app_name)
    val subtitle = stringResource(R.string.biometric_prompt_subtitle_unlock)
    val negative = stringResource(R.string.biometric_prompt_negative)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    fun promptBiometric() {
        val cipher = viewModel.biometricCipher() ?: return
        val host = activity ?: return
        scope.launch { viewModel.onBiometricResult(host.authenticateWithBiometrics(cipher, title, subtitle, negative)) }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.biometricEnabled) {
        if (state.biometricEnabled && !autoPrompted) {
            autoPrompted = true
            promptBiometric()
        }
    }
    LaunchedEffect(state.lockoutUntilEpochMs) {
        while (System.currentTimeMillis() < state.lockoutUntilEpochMs) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val lockedOut = now < state.lockoutUntilEpochMs

    AccessLayout {
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.unlock_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!state.fatal) {
            PasswordField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(R.string.field_password),
                enabled = !state.working && !lockedOut,
                isError = state.message is UnlockMessage.Wrong,
                modifier = Modifier.testTag("unlock_password"),
            )
            Button(
                onClick = viewModel::unlock,
                enabled = !state.working && !lockedOut && state.password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unlock_submit"),
            ) {
                Text(stringResource(if (state.working) R.string.unlock_checking else R.string.unlock_action))
            }
            if (state.biometricEnabled) {
                OutlinedButton(onClick = ::promptBiometric, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Face, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.unlock_biometric))
                }
            }
        }

        state.message?.let { message ->
            val text = when (message) {
                is UnlockMessage.Wrong -> message.attemptsBeforeWipe?.let { pluralStringResource(R.plurals.unlock_wrong_wipe, it, it) }
                    ?: pluralStringResource(R.plurals.unlock_wrong, message.failures, message.failures)
                is UnlockMessage.LockedOut ->
                    if (lockedOut) stringResource(R.string.unlock_locked_out, formatRemaining(state.lockoutUntilEpochMs - now)) else null
                UnlockMessage.Wiped -> stringResource(R.string.unlock_wiped)
                UnlockMessage.DeviceKeyLost -> stringResource(R.string.unlock_device_key_lost)
                UnlockMessage.Corrupted -> stringResource(R.string.unlock_corrupted)
                UnlockMessage.BiometricInvalidated -> stringResource(R.string.unlock_biometric_invalidated)
                UnlockMessage.BiometricFailed -> stringResource(R.string.unlock_biometric_failed)
            }
            if (text != null) {
                FuncodePanel(accent = MaterialTheme.colorScheme.error) {
                    Text(text, modifier = Modifier.testTag("unlock_message"))
                }
            }
        }

        if (state.fatal) {
            Button(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unlock_wipe_everything))
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.dialog_wipe_title)) },
            text = { Text(stringResource(R.string.settings_wipe_all_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    viewModel.wipeEverything()
                }) { Text(stringResource(R.string.action_wipe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

internal fun formatRemaining(millis: Long): String {
    val totalSeconds = ((millis + 999) / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d min %02d s".format(minutes, seconds) else "$seconds s"
}
