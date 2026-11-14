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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arka planda sürekli dinler. Kalıcı bildirim: bekleme / "Gönderiliyor: dosya" / "Alınıyor: dosya" + ilerleme.
 * Gönderimler de burada (tek iş parçacığında, sırayla) yapılır; paylaş ekranı hemen kapanabilir.
 */
class ReceiverService : Service() {
    private var rx: Receiver? = null
    private var disc: Discovery? = null
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private val back = Runnable { show(idleText(), -1) }
    private var curText = ""
    private var curPct = -1

    // Sabit PendingIntent'ler bir kez kurulur; Builder her güncellemede cihaz butonlarıyla yeniden üretilir.
    private val stopPi by lazy {
        PendingIntent.getService(this, 0, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    }
    private val openPi by lazy {
        PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).putExtra("folder", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    /** Bildirimde en fazla 3 buton gösterilir: [Durdur] her zaman var, kalan 2 yer çevrimiçi cihazlara ayrılır. */
    private fun builder(text: String): Notification.Builder {
        val ps = Peers.list()
        val b = Notification.Builder(this, "qs_run")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.tile_label))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val slots = 2
        // 2'den fazla cihaz varsa 1 cihaz + "Diğerleri" (uygulamayı açar, tam liste orada)
        val shown = if (ps.size > slots) ps.take(slots - 1) else ps
        for (p in shown) {
            val pi = PendingIntent.getActivity(this, p.id.hashCode(),
                Intent(this, PickActivity::class.java).putExtra("to", p.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val label = "→ " + p.name.ifBlank { getString(R.string.device_default) }.take(20)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), label, pi).build())
        }
        if (ps.size > slots)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile),
                getString(R.string.notif_more, ps.size - shown.size), morePi).build())
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), getString(R.string.notif_stop), stopPi).build())
        return b
    }

    private val morePi by lazy {
        PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Boştayken bildirim metni: çevrimiçi cihazların adları (yoksa "Gönderi bekleniyor"). */
    private fun idleText(): String {
        val ps = Peers.list()
        return if (ps.isEmpty()) getString(R.string.notif_title)
        else getString(R.string.notif_online, ps.joinToString(", ") { it.name.ifBlank { getString(R.string.device_default) } })
    }

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "io.qs.STOP"
        const val ACTION_SEND = "io.qs.SEND"

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

        /** Dosya geldiğinde ayrı, açılır bildirim. Dokununca Link klasörü açılır. */
        fun notifyReceived(c: Context, name: String) {
            val i = Intent(c, MainActivity::class.java).putExtra("folder", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, name.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "qs_recv")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(c.getString(R.string.notif_received))
                .setContentText(name)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(1000 + (System.nanoTime() % 100000).toInt().let { if (it < 0) -it else it }, n)
        }
    }

    override fun onBind(i: Intent?) = null

    private fun show(text: String, pct: Int) {
        try {
            curText = text; curPct = pct
            val n = builder(text).setProgress(if (pct >= 0) 100 else 0, pct.coerceAtLeast(0), false).build()
            getSystemService(NotificationManager::class.java).notify(1, n)
        } catch (e: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Identity.init(this)
        channels(this)
        startForeground(1, builder(idleText()).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        // Gönderme/alma ilerlemesini bildirime yansıt
        Bus.sink = { s, p ->
            h.post {
                h.removeCallbacks(back)
                if (s.isNotEmpty()) show(s, p)
                if (p < 0) h.postDelayed(back, 4000)
            }
        }

        // Cihaz listesi değişince bildirimdeki cihaz butonlarını tazele (aktarım sürerken ilerleme metni korunur)
        Peers.onChange = {
            h.post { if (curPct < 0) show(idleText(), -1) else show(curText, curPct) }
        }

        rx = Receiver(this).also { it.start() }
        disc = Discovery(this).also { it.register(rx!!.port); it.browse() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val it = i ?: return START_STICKY   // sistem yeniden başlatınca intent null gelir
        when (it.action) {
            ACTION_STOP -> {
                getSharedPreferences("cfg", 0).edit().putBoolean("on", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND -> {
                val cd = it.clipData
                val uris = (0 until (cd?.itemCount ?: 0)).mapNotNull { n -> cd?.getItemAt(n)?.uri }
                val only = it.getStringExtra("only")
                if (uris.isNotEmpty()) {
                    pending.incrementAndGet()
                    io.execute {
                        try { Sender.run(this, only, uris) }
                        finally {
                            // Kullanıcı dinlemeyi kapattıysa, gönderim bitince servis de kapanır.
                            if (pending.decrementAndGet() == 0 && !enabled(this)) stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bus.sink = null
        Peers.onChange = null
        h.removeCallbacksAndMessages(null)
        io.shutdown()
        disc?.stop(); rx?.stop()
        super.onDestroy()
    }
}
