package com.funcodestudiocb.analyzer.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.ui.access.SetupScreen
import com.funcodestudiocb.analyzer.ui.access.SetupViewModel
import com.funcodestudiocb.analyzer.ui.access.UnlockScreen
import com.funcodestudiocb.analyzer.ui.access.UnlockViewModel
import com.funcodestudiocb.analyzer.ui.main.MainScaffold

/** Crea un ViewModel con dependencias del [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, noinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/**
 * Raíz de la interfaz: según el estado de la sesión muestra la configuración inicial, el desbloqueo
 * o la app. Al bloquearse, toda la interfaz de la app se descarta.
 */
@Composable
fun FuncodeRoot(container: AppContainer) {
    val session by container.session.state.collectAsStateWithLifecycle()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val widthDp = maxWidth.value.toInt()
        Crossfade(targetState = session, label = "sesion") { state ->
            when (state) {
                SessionState.NotConfigured -> {
                    var wipeNotice by remember { mutableStateOf(container.hasWipeNotice()) }
                    SetupScreen(
                        containerViewModel("setup") { SetupViewModel(container.vault, container.session) },
                        wipeNotice = wipeNotice,
                        onDismissNotice = {
                            container.dismissWipeNotice()
                            wipeNotice = false
                        },
                    )
                }
                SessionState.Locked ->
                    UnlockScreen(containerViewModel("unlock") { UnlockViewModel(container.vault, container.session) })
                SessionState.Unlocked -> MainScaffold(container, widthDp)
            }
        }
    }
}
