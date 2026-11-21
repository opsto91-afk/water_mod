package org.levimc.zaphre.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.levimc.zaphre.MainActivity
import org.levimc.zaphre.R
import org.levimc.zaphre.ui.MainViewModel

/** Keeps an active, user-started turn foreground while the process is alive. */
class AgentForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "RE Agent jobs", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PETA RE agent running")
            .setContentText("Tap to view progress, pause or stop")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
        } catch (_: Exception) {
            MainViewModel.markAgentServiceReady(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (MainViewModel.isAgentActive()) MainViewModel.markAgentServiceReady(true)
        else if (intent == null) MainViewModel.forService(application).recoverAgentFromService()
        else stopSelf()
        // Android may restart a killed service; recovery only runs from a safe checkpoint.
        return START_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        MainViewModel.cancelFromService()
        stopSelf(startId)
    }
    companion object {
        private const val CHANNEL = "peta_agent_jobs"
        private const val NOTIFICATION = 71293
    }
}
