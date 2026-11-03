package com.funcodestudiocb.analyzer

import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.security.AutoLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AutoLockTest {

    private lateinit var container: AppContainer
    private val scope = TestScope()
    private val owner = TestLifecycleOwner()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
    }

    private fun autoLock() = AutoLock(container.vault, container.session, scope) { scope.testScheduler.currentTime }

    @Test
    fun seBloqueaTrasElTiempoEnSegundoPlano() {
        val lock = autoLock()
        lock.onStop(owner)
        scope.advanceTimeBy(59_000)
        scope.runCurrent()
        assertEquals(SessionState.Unlocked, container.session.state.value)
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(SessionState.Locked, container.session.state.value)
    }

    @Test
    fun conBloqueoInmediatoSeBloqueaAlSalir() {
        container.vault.updateSettings { it.copy(backgroundLockSeconds = 0) }
        autoLock().onStop(owner)
        assertEquals(SessionState.Locked, container.session.state.value)
    }

    @Test
    fun seBloqueaPorInactividad() {
        container.vault.updateSettings { it.copy(inactivityLockMinutes = 1) }
        val lock = autoLock()
        lock.onUserInteraction()
        scope.advanceTimeBy(30_000)
        lock.onUserInteraction()
        scope.advanceTimeBy(45_000)
        scope.runCurrent()
        assertEquals(SessionState.Unlocked, container.session.state.value)
        scope.advanceTimeBy(20_000)
        scope.runCurrent()
        assertEquals(SessionState.Locked, container.session.state.value)
    }
}
