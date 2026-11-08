package io.qs

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLDecoder
import java.net.URLEncoder
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

private const val TYPE = "_qshare._tcp."
private const val BUF = 1 shl 20

/** mDNS (NsdManager): kendini duyurur ve çevredeki cihazları bulur. */
class Discovery(c: Context) {
    private val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var rl: NsdManager.RegistrationListener? = null
    private var dl: NsdManager.DiscoveryListener? = null
    private val q = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private val myName get() = "QS" + Identity.fp.take(10)

    fun register(port: Int) {
        val i = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = TYPE
            this.port = port
            setAttribute("n", Identity.name)
            setAttribute("f", Identity.fp)
        }
        rl = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        nsd.registerService(i, NsdManager.PROTOCOL_DNS_SD, rl)
    }

    fun browse() {
        dl = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName != myName) enqueue(s) }
            override fun onServiceLost(s: NsdServiceInfo) { Peers.del(s.serviceName) }
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
    }

    fun stop() {
        try { rl?.let { nsd.unregisterService(it) } } catch (e: Exception) { }
        try { dl?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
    }

    @Synchronized private fun enqueue(s: NsdServiceInfo) { q.addLast(s); pump() }

    @Synchronized private fun pump() {
        if (busy) return
        val s = q.removeFirstOrNull() ?: return
        busy = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { done() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val f = i.attributes["f"]?.toString(Charsets.UTF_8)
                val n = i.attributes["n"]?.toString(Charsets.UTF_8)
                if (f != null && f != Identity.fp && i.host != null)
                    Peers.put(i.serviceName, Peer(f, n ?: "?", i.host, i.port))
                done()
            }
        })
    }

    @Synchronized private fun done() { busy = false; pump() }
}

/** TLS sunucu: sadece güvenilir parmak izli cihazlardan dosya kabul eder. */
class Receiver(private val ctx: Context) {
    private var ss: SSLServerSocket? = null
    val port get() = ss!!.localPort

    fun start() {
        val s = (Identity.ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket)
        s.wantClientAuth = true
        ss = s
        thread(isDaemon = true) {
            while (true) {
                val c = try { s.accept() as SSLSocket } catch (e: Exception) { break }
                thread { handle(c) }
            }
        }
    }

    fun stop() { try { ss?.close() } catch (e: Exception) { } }

    private fun line(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun reply(s: SSLSocket, st: String) {
        s.outputStream.write("HTTP/1.1 $st\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.outputStream.flush()
    }

    private fun handle(s: SSLSocket) {
        s.use {
            try {
                s.soTimeout = 30000
                s.startHandshake()
                val fp = try { Identity.fpOf(s.session.peerCertificates[0]) } catch (e: Exception) { null }
                if (fp == null || !Trust.has(fp)) { reply(s, "403 Forbidden"); return }
                val ins = s.inputStream
                line(ins)
                var len = -1L
                var name = "file"
                while (true) {
                    val l = line(ins)
                    if (l.isEmpty()) break
                    val k = l.substringBefore(':').lowercase()
                    val v = l.substringAfter(':').trim()
                    if (k == "content-length") len = v.toLong()
                    else if (k == "x-name") name = URLDecoder.decode(v, "UTF-8")
                }
                if (len < 0) { reply(s, "400 Bad Request"); return }
                val clean = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trimStart('.').take(120).ifBlank { "file" }

                val cr = ctx.contentResolver
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, clean)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/QuickShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                var ok = false
                try {
                    cr.openOutputStream(uri)!!.use { out ->
                        val b = ByteArray(BUF)
                        var left = len
                        var last = -1
                        while (left > 0) {
                            val n = ins.read(b, 0, minOf(b.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(b, 0, n)
                            left -= n
                            val p = ((len - left) * 100 / len).toInt()
                            if (p != last) { last = p; Bus.set("Alınıyor: $clean", p) }
                        }
                        ok = left == 0L
                    }
                } finally {
                    if (ok) {
                        cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                        cr.update(uri, cv, null, null)
                    } else cr.delete(uri, null, null)
                }
                reply(s, if (ok) "200 OK" else "500 Error")
                Bus.set(if (ok) "Kaydedildi: $clean" else "Alma yarım kaldı")
            } catch (e: Exception) {
                Bus.set("Alma hatası")
            }
        }
    }
}

object Sender {
    /** Güvenilir cihaz yoksa uyarır, tek ise direkt gönderir, çoksa seçtirir. */
    fun start(a: Activity, uris: List<Uri>, only: String?, fin: () -> Unit) {
        val ps = Peers.list().filter { Trust.has(it.fp) && (only == null || it.fp == only) }
        fun go(p: Peer) = send(a.applicationContext, p, uris) { m ->
            a.runOnUiThread { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); fin() }
        }
        when {
            ps.isEmpty() -> {
                Toast.makeText(a, "Güvenilir cihaz bulunamadı", Toast.LENGTH_LONG).show(); fin()
            }
            ps.size == 1 -> go(ps[0])
            else -> {
                var sel = ps.indexOfFirst { it.fp == Trust.last }.coerceAtLeast(0)
                AlertDialog.Builder(a).setTitle("Hangi cihaza?")
                    .setSingleChoiceItems(ps.map { it.name }.toTypedArray(), sel) { _, w -> sel = w }
                    .setPositiveButton("Gönder") { _, _ -> go(ps[sel]) }
                    .setNegativeButton("İptal") { _, _ -> fin() }
                    .setOnCancelListener { fin() }
                    .show()
            }
        }
    }

    fun send(c: Context, p: Peer, uris: List<Uri>, done: (String) -> Unit) = thread {
        var msg = "Gönderildi"
        try { uris.forEach { one(c, p, it) } } catch (e: Exception) { msg = "Hata: " + (e.message ?: "bağlantı") }
        if (msg == "Gönderildi") Trust.last = p.fp
        Bus.set(msg)
        done(msg)
    }

    private fun one(c: Context, p: Peer, u: Uri) {
        val cr = c.contentResolver
        var name = "file"
        cr.query(u, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = it.getString(i)
            }
        }
        val afd = cr.openAssetFileDescriptor(u, "r") ?: error("Dosya açılamadı")
        val len = afd.length
        require(len >= 0) { "Dosya boyutu bilinmiyor" }
        afd.createInputStream().use { ins ->
            (Identity.ssl.socketFactory.createSocket(p.host, p.port) as SSLSocket).use { s ->
                s.tcpNoDelay = true
                s.sendBufferSize = BUF
                s.soTimeout = 30000
                s.startHandshake()
                require(Identity.fpOf(s.session.peerCertificates[0]) == p.fp) { "Cihaz doğrulanamadı" }
                val out = s.outputStream
                out.write(
                    ("POST /u HTTP/1.1\r\nX-Name: ${URLEncoder.encode(name, "UTF-8")}\r\n" +
                        "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray()
                )
                val b = ByteArray(BUF)
                var sent = 0L
                var last = -1
                while (true) {
                    val n = ins.read(b)
                    if (n < 0) break
                    out.write(b, 0, n)
                    sent += n
                    val pc = if (len > 0) (sent * 100 / len).toInt() else 0
                    if (pc != last) { last = pc; Bus.set("Gönderiliyor: $name", pc) }
                }
                out.flush()
                val l = BufferedReader(InputStreamReader(s.inputStream)).readLine()
                require(l?.contains(" 200") == true) { "Karşı cihaz kabul etmedi (güvenilir listesinde değilim)" }
            }
        }
    }
}
