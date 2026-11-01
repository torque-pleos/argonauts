package com.zigae.argonauts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.zigae.argonauts.MainActivity
import com.zigae.argonauts.R

/**
 * 크루에 있는 동안 프로세스를 유지하는 포그라운드 서비스.
 * 내비 화면이 앞에 있어도 위치 공유·카드 계산이 계속되게 한다 (검증 README 7-1).
 * 실제 로직은 Application 범위의 CrewEngine이 담당한다.
 */
class CrewService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "크루 위치 공유", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Argonauts")
            .setContentText("크루와 위치·목적지를 공유하고 있어요")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "crew"
    }
}
