package io.qs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Arka planda sürekli dinler. Kalıcı bildirim gösterir:
 * bekleme / "Gönderiliyor: dosya" / "Alınıyor: dosya" + ilerleme çubuğu.
 */
class ReceiverService : Service() {
    private var rx: Receiver? = null
    private var disc: Discovery? = null
    private var lock: WifiManager.WifiLock? = null
    private val h = Handler(Looper.getMainLooper())
    private val back = Runnable { show(getString(R.string.notif_title), -1) }

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "io.qs.STOP"

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("cfg", 0)
        fun enabled(c: Context) = prefs(c).getBoolean("on", true)

        /** Bildirim izni (Android 13+) ve dosya erişimi verilmiş mi? */
        fun ready(c: Context): Boolean {
            val notif = Build.VERSION.SDK_INT < 33 ||
                c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            return notif && SaveDir.direct(c)
        }

        fun start(c: Context) {
            prefs(c).edit().putBoolean("on", true).apply()
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }

        fun stop(c: Context) {
            prefs(c).edit().putBoolean("on", false).apply()
            c.stopService(Intent(c, ReceiverService::class.java))
        }

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("qs")
            nm.createNotificationChannel(NotificationChannel("qs_run", c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel("qs_recv", c.getString(R.string.notif_recv_channel), NotificationManager.IMPORTANCE_HIGH))
        }

        /** Dosya geldiğinde ayrı, sesli/açılır bildirim. Dokununca Link klasörü açılır. */
        fun notifyReceived(c: Context, name: String) {
            channels(c)
            val i = Intent(c, MainActivity::class.java).putExtra("folder", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, name.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "qs_recv")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(c.getString(R.string.notif_received))
                .setContentText(name)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(1000 + (System.nanoTime() % 100000).toInt(), n)
        }
    }

    override fun onBind(i: Intent?) = null

    private fun build(text: String, pct: Int): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "qs_run")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.tile_label))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (pct >= 0) setProgress(100, pct, false) }
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile), getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun show(text: String, pct: Int) {
        try { getSystemService(NotificationManager::class.java).notify(1, build(text, pct)) } catch (e: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Identity.init(this)
        channels(this)
        startForeground(1, build(getString(R.string.notif_title), -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        // Gönderme/alma ilerlemesini bildirime yansıt
        Bus.sink = { s, p ->
            h.post {
                h.removeCallbacks(back)
                if (s.isNotEmpty()) show(s, p)
                if (p < 0) h.postDelayed(back, 4000)
            }
        }

        lock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "qs").apply { acquire() }
        rx = Receiver(this).also { it.start() }
        disc = Discovery(this).also { it.register(rx!!.port); it.browse() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) {
            getSharedPreferences("cfg", 0).edit().putBoolean("on", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bus.sink = null
        h.removeCallbacksAndMessages(null)
        disc?.stop(); rx?.stop()
        try { lock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}
