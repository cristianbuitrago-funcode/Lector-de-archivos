package com.funcodestudiocb.analyzer.data.session

import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    /** Primera ejecución: hay que crear el acceso del propietario. */
    data object NotConfigured : SessionState

    data object Locked : SessionState

    data object Unlocked : SessionState
}

/**
 * Estado de la sesión del propietario. Mientras está bloqueada, la base de datos está cerrada y su clave
 * no está en memoria.
 */
class SessionManager(
    private val isConfigured: () -> Boolean,
    private val openDatabase: (ByteArray) -> FuncodeDatabase,
) {
    private val lock = Any()
    private var database: FuncodeDatabase? = null
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private fun initialState(): SessionState = if (isConfigured()) SessionState.Locked else SessionState.NotConfigured

    /** Abre la sesión con la clave de la BD. La clave se borra de memoria. */
    fun unlock(databaseKey: ByteArray) {
        synchronized(lock) {
            try {
                database?.close()
                database = openDatabase(databaseKey)
            } finally {
                databaseKey.fill(0)
            }
            _state.value = SessionState.Unlocked
        }
    }

    fun lock() {
        synchronized(lock) {
            database?.close()
            database = null
            _state.value = initialState()
        }
    }

    /** Vuelve a evaluar el estado inicial (p. ej. tras un borrado total). */
    fun reset() = lock()

    fun requireDatabase(): FuncodeDatabase =
        synchronized(lock) { database ?: throw IllegalStateException("La sesión está bloqueada") }

    fun databaseOrNull(): FuncodeDatabase? = synchronized(lock) { database }
}
