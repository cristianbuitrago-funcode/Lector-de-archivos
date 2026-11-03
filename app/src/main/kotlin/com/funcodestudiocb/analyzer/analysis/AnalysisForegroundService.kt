package com.funcodestudiocb.analyzer.analysis

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano mientras dura un análisis: evita que Android detenga la app si el usuario sale
 * de ella. El trabajo lo hace el [AnalysisCoordinator]; este servicio solo muestra el progreso.
 *
 * La notificación es privada: en la pantalla de bloqueo no muestra el nombre del proyecto.
 */
class AnalysisForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(null), type)
        val coordinator = (application as FuncodeApp).container.coordinator
        scope.launch {
            coordinator.state.collect { state ->
                when (state) {
                    is AnalysisState.Running -> notify(build(state))
                    AnalysisState.Importing -> Unit
                    else -> stopSelfNow()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun build(state: AnalysisState.Running?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_analysis)
            .setContentTitle(getString(R.string.notif_running_public))
            .build()
        val percent = ((state?.overall ?: 0f) * 100).toInt()
        val stage = state?.stages?.lastOrNull { it.title.isNotEmpty() }?.title
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_analysis)
            .setContentTitle(state?.let { getString(R.string.notif_running_title, it.archive.displayName) } ?: getString(R.string.notif_running_public))
            .setContentText(stage ?: getString(R.string.notif_preparing))
            .setProgress(100, percent, state == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "analisis"
        private const val NOTIFICATION_ID = 2001

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                        setShowBadge(false)
                    },
                )
            }
        }

        /** Controlador para el coordinador. */
        fun controller(context: Context): ForegroundController = object : ForegroundController {
            private val app = context.applicationContext
            override fun start() {
                runCatching { ContextCompat.startForegroundService(app, Intent(app, AnalysisForegroundService::class.java)) }
            }

            override fun stop() = Unit // El servicio se detiene solo al ver que el análisis terminó.
        }
    }
}
