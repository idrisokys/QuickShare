package io.qs

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.net.InetAddress
import java.util.UUID

class Peer(val id: String, val name: String, val host: InetAddress, val port: Int)

/** UI'ya durum bildirimi (ana thread'e taşır). */
object Bus {
    private val h = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var status = ""
    @Volatile var pct = -1
    /** true ise durum yazısı "Kaydedildi" mesajıdır; tıklayınca klasör açılır. */
    @Volatile var saved = false
    fun changed() { h.post { listener?.invoke() } }
    fun set(s: String, p: Int = -1, saved: Boolean = false) { status = s; pct = p; this.saved = saved; changed() }
}

/** Ağda bulunan cihazlar. */
object Peers {
    private val m = LinkedHashMap<String, Peer>()
    @Synchronized fun put(svc: String, p: Peer) { m[svc] = p; Bus.changed() }
    @Synchronized fun del(svc: String) { m.remove(svc); Bus.changed() }
    @Synchronized fun list(): List<Peer> = m.values.distinctBy { it.id }
}

/** Son gönderim yapılan cihazın kimliği (seçim penceresinde ön seçim için). */
object Last {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("last", 0) }
    var id: String?
        get() = sp.getString("id", null)
        set(v) = sp.edit().putString("id", v).apply()
}

/** Cihaz kimliği: ilk açılışta üretilen rastgele kimlik (şifreleme / sertifika yok). */
object Identity {
    lateinit var id: String
    lateinit var name: String
    private var ok = false

    @Synchronized
    fun init(c: Context) {
        if (ok) return
        val app = c.applicationContext
        Last.init(app)
        name = Settings.Global.getString(c.contentResolver, "device_name") ?: Build.MODEL
        val sp = app.getSharedPreferences("identity", 0)
        id = sp.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { sp.edit().putString("id", it).apply() }
        ok = true
    }
}

/** Ağdaki cihazları paylaş menüsünde doğrudan hedef olarak gösterir. */
object Shortcuts {
    fun update(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java)
            sm.dynamicShortcuts = Peers.list().take(4).map {
                ShortcutInfo.Builder(c, it.id)
                    .setShortLabel(it.name.ifBlank { c.getString(R.string.device_default) }.take(25))
                    .setIcon(Icon.createWithResource(c, R.drawable.ic))
                    .setCategories(setOf("io.qs.TARGET"))
                    .setLongLived(true)
                    .setIntent(Intent(c, ShareActivity::class.java).setAction("io.qs.SHORT"))
                    .build()
            }
        } catch (e: Exception) { }
    }
}
