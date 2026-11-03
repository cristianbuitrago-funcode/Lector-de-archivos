package com.funcodestudiocb.analyzer.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.funcodestudiocb.analyzer.data.session.SessionManager
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.data.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bloqueo automático de la sesión:
 * - al pasar la app a segundo plano, tras el tiempo configurado;
 * - tras un periodo sin interacción.
 * Bloquear cierra la base de datos y elimina su clave de la memoria.
 */
class AutoLock(
    private val vault: Vault,
    private val session: SessionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {

    private var backgroundSince: Long? = null
    private var backgroundJob: Job? = null
    private var inactivityJob: Job? = null
    private var lastInteraction = now()

    override fun onStop(owner: LifecycleOwner) {
        if (session.state.value != SessionState.Unlocked) return
        backgroundSince = now()
        val delayMs = vault.settings().backgroundLockSeconds * 1000L
        if (delayMs == 0L) {
            session.lock()
            return
        }
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(delayMs)
            session.lock()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        backgroundJob?.cancel()
        val since = backgroundSince
        backgroundSince = null
        if (since != null && now() - since >= vault.settings().backgroundLockSeconds * 1000L) {
            session.lock()
        }
        onUserInteraction()
    }

    /** Llamar en cada interacción del usuario (lo hace la actividad principal). */
    fun onUserInteraction() {
        lastInteraction = now()
        if (inactivityJob?.isActive == true) return
        inactivityJob = scope.launch {
            while (session.state.value == SessionState.Unlocked) {
                val limit = vault.settings().inactivityLockMinutes * 60_000L
                val idle = now() - lastInteraction
                if (idle >= limit) {
                    session.lock()
                    break
                }
                delay(limit - idle)
            }
        }
    }
}
