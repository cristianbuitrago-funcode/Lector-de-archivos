package com.funcodestudiocb.analyzer.service

import android.app.Application
import android.os.Build
import android.os.Process
import java.io.File

/** Información del proceso actual. */
object ProcessInfo {

    private const val PER_USER_RANGE = 100_000
    private const val FIRST_ISOLATED_APP_ID = 99_000
    private const val LAST_ISOLATED_APP_ID = 99_999
    private const val FIRST_APP_ZYGOTE_ISOLATED_APP_ID = 90_000
    private const val LAST_APP_ZYGOTE_ISOLATED_APP_ID = 98_999

    /**
     * Indica si el proceso actual es un proceso aislado del sistema.
     *
     * Importante: la clase `Application` de la app también se instancia dentro del proceso aislado,
     * así que su inicialización debe comprobar esto y no tocar almacenamiento ni base de datos.
     */
    fun isIsolatedProcess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Process.isIsolated()
        } else {
            isIsolatedUid(Process.myUid())
        }

    /** Comprobación por rango de UID (para Android 8.x, donde no existe `Process.isIsolated()`). */
    fun isIsolatedUid(uid: Int): Boolean {
        val appId = uid % PER_USER_RANGE
        return appId in FIRST_ISOLATED_APP_ID..LAST_ISOLATED_APP_ID ||
            appId in FIRST_APP_ZYGOTE_ISOLATED_APP_ID..LAST_APP_ZYGOTE_ISOLATED_APP_ID
    }

    fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
            }.getOrDefault("desconocido")
        }
}
