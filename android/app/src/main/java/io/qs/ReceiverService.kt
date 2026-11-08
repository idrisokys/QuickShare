package io.qs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager

/** Uygulama açıkken dinler; uygulama kapanınca durur. */
class ReceiverService : Service() {
    private var rx: Receiver? = null
    private var disc: Discovery? = null
    private var lock: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Identity.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("qs", getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "qs")
            .setContentTitle(getString(R.string.notif_title))
            .setSmallIcon(R.drawable.ic)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        lock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "qs").apply { acquire() }
        rx = Receiver(this).also { it.start() }
        disc = Discovery(this).also { it.register(rx!!.port); it.browse() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_NOT_STICKY

    override fun onTaskRemoved(i: Intent?) { stopSelf() }

    override fun onDestroy() {
        disc?.stop(); rx?.stop()
        try { lock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}
