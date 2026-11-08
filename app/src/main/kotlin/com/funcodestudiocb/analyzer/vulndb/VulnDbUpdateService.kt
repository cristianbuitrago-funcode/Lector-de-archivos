package com.funcodestudiocb.analyzer.vulndb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.funcodestudiocb.analyzer.FuncodeApp
import com.funcodestudiocb.analyzer.MainActivity
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.analysis.ForegroundController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano mientras se actualiza la base de vulnerabilidades: la descarga puede durar minutos y no debe
 * cortarse si el usuario sale de la app. El trabajo lo hace [VulnDbUpdater]; este servicio solo muestra el progreso.
 */
class VulnDbUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(null), type)
        lastStartId = startId
        if (watcher?.isActive != true) {
            val updater = (application as FuncodeApp).container.vulnDbUpdater
            watcher = scope.launch {
                updater.state.collect { state ->
                    if (state is UpdateState.Running) notify(build(state)) else stopIfIdle()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (stopSelfResult(lastStartId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            watcher?.cancel()
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun build(state: UpdateState.Running?): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_analysis)
            .setContentTitle(getString(R.string.vulndb_notif_title))
            .setContentText(state?.step ?: getString(R.string.notif_preparing))
            .setProgress(100, ((state?.overall ?: 0f) * 100).toInt(), state == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "base_vulnerabilidades"
        private const val NOTIFICATION_ID = 2002

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.vulndb_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
        }

        fun controller(context: Context): ForegroundController = object : ForegroundController {
            private val app = context.applicationContext
            override fun start() {
                runCatching { ContextCompat.startForegroundService(app, Intent(app, VulnDbUpdateService::class.java)) }
            }

            override fun stop() = Unit
        }
    }
}
