package io.qs

import android.annotation.SuppressLint
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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class Peer(val fp: String, val name: String, val host: InetAddress, val port: Int)

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
    @Synchronized fun list(): List<Peer> = m.values.distinctBy { it.fp }
}

/** Son gönderim yapılan cihazın parmak izi (seçim penceresinde ön seçim için). */
object Last {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("last", 0) }
    var fp: String?
        get() = sp.getString("fp", null)
        set(v) = sp.edit().putString("fp", v).apply()
}

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
object TrustAll : X509TrustManager {
    override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
    override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/** Cihaz kimliği: AndroidKeyStore içinde EC anahtar + otomatik self-signed sertifika. */
object Identity {
    lateinit var fp: String
    lateinit var name: String
    lateinit var ssl: SSLContext
    private var ok = false

    @Synchronized
    fun init(c: Context) {
        if (ok) return
        Last.init(c.applicationContext)
        name = Settings.Global.getString(c.contentResolver, "device_name") ?: Build.MODEL
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias("qs")) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(
                KeyGenParameterSpec.Builder("qs", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE
                    )
                    .setCertificateSubject(X500Principal("CN=QuickShare"))
                    .build()
            )
            g.generateKeyPair()
        }
        fp = fpOf(ks.getCertificate("qs"))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(TrustAll), null) }
        ok = true
    }

    fun fpOf(c: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }
}

/** Ağdaki cihazları paylaş menüsünde doğrudan hedef olarak gösterir. */
object Shortcuts {
    fun update(c: Context) {
        try {
            val sm = c.getSystemService(ShortcutManager::class.java)
            sm.dynamicShortcuts = Peers.list().take(4).map {
                ShortcutInfo.Builder(c, it.fp)
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
