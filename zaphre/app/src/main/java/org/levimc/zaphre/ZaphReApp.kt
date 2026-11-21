package org.levimc.zaphre

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ZaphReApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        org.levimc.zaphre.ai.AiApiClient.initialize(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BG_CHANNEL_ID,
                getString(R.string.bg_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.bg_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val BG_CHANNEL_ID = "zaphre_bg_mc_runner"

        @Volatile
        lateinit var instance: ZaphReApp
            private set
    }
}
