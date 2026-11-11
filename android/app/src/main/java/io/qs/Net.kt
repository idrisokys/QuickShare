package io.qs

import android.app.Activity
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

private const val TYPE = "_qshare._tcp."
private const val BUF = 1 shl 20

/** Alınan dosyaların varsayılan konumu: <ana dizin>/Link */
object SaveDir {
    fun dir() = File(android.os.Environment.getExternalStorageDirectory(), "Link")
    /** Android 11+: "Tüm dosyalara erişim", Android 10: depolama izni verilmişse ana dizine doğrudan yazılabilir. */
    fun direct(c: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** mDNS (NsdManager): kendini duyurur ve çevredeki cihazları bulur. */
class Discovery(c: Context) {
    private val app = c.applicationContext
    private val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var rl: NsdManager.RegistrationListener? = null
    private var dl: NsdManager.DiscoveryListener? = null
    private val q = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private val myName get() = "QS" + Identity.id.take(10)

    fun register(port: Int) {
        val i = NsdServiceInfo().apply {
            serviceName = myName
            serviceType = TYPE
            this.port = port
            setAttribute("n", Identity.name)
            setAttribute("i", Identity.id)
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
            override fun onServiceLost(s: NsdServiceInfo) { Peers.del(s.serviceName); Shortcuts.update(app) }
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
                val f = i.attributes["i"]?.toString(Charsets.UTF_8)
                val n = i.attributes["n"]?.toString(Charsets.UTF_8)
                if (f != null && f != Identity.id && i.host != null)
                    { Peers.put(i.serviceName, Peer(f, n ?: "?", i.host, i.port)); Shortcuts.update(app) }
                done()
            }
        })
    }

    @Synchronized private fun done() { busy = false; pump() }
}

/** TCP sunucu: ağdaki cihazlardan gelen dosyaları kabul eder. */
class Receiver(private val ctx: Context) {
    private var ss: ServerSocket? = null
    val port get() = ss!!.localPort

    fun start() {
        val s = ServerSocket(0)
        ss = s
        thread(isDaemon = true) {
            while (true) {
                val c = try { s.accept() } catch (e: Exception) { break }
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

    private fun reply(s: Socket, st: String) {
        s.outputStream.write("HTTP/1.1 $st\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.outputStream.flush()
    }

    private fun handle(s: Socket) {
        s.use {
            try {
                s.soTimeout = 30000
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

                // Kayıt yeri yalnızca <ana dizin>/Link; izin yoksa ya da yazılamazsa alma reddedilir.
                if (!SaveDir.direct(ctx)) { reply(s, "500 Error"); Bus.set(ctx.getString(R.string.recv_error)); return }
                val d = SaveDir.dir().also { it.mkdirs() }
                if (!d.isDirectory) { reply(s, "500 Error"); Bus.set(ctx.getString(R.string.recv_error)); return }
                // Aynı adlı dosya varsa üzerine yazılır; önce .part dosyasına yazılır, başarılı olunca yerine konur.
                val file = File(d, "$clean.part")
                val os = FileOutputStream(file)
                var ok = false
                try {
                    os.use { out ->
                        val b = ByteArray(BUF)
                        var left = len
                        var last = -1
                        while (left > 0) {
                            val n = ins.read(b, 0, minOf(b.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(b, 0, n)
                            left -= n
                            val p = ((len - left) * 100 / len).toInt()
                            if (p != last) { last = p; Bus.set(ctx.getString(R.string.receiving, clean), p) }
                        }
                        ok = left == 0L
                    }
                } finally {
                    if (ok) {
                        val dest = File(d, clean)
                        dest.delete()
                        if (file.renameTo(dest)) MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
                        else { file.delete(); ok = false }
                    } else file.delete()
                }
                reply(s, if (ok) "200 OK" else "500 Error")
                Bus.set(if (ok) ctx.getString(R.string.saved, clean) else ctx.getString(R.string.recv_partial), -1, ok)
            } catch (e: Exception) {
                Bus.set(ctx.getString(R.string.recv_error))
            }
        }
    }
}

object Sender {
    /** Cihaz yoksa uyarır, tek ise direkt gönderir, çoksa seçtirir. only verilirse sadece o cihaza gönderir. */
    fun start(a: Activity, uris: List<Uri>, only: String?, fin: () -> Unit) {
        val ps = Peers.list().filter { only == null || it.id == only }
        if (ps.isEmpty()) {
            Toast.makeText(a, a.getString(R.string.no_device), Toast.LENGTH_LONG).show(); fin()
            return
        }
        // only verilmediyse bulunan tüm cihazlara sırayla gönderir (biri bitince diğerine geçer).
        send(a.applicationContext, ps, uris) { m ->
            a.runOnUiThread { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); fin() }
        }
    }

    fun send(c: Context, ps: List<Peer>, uris: List<Uri>, done: (String) -> Unit) = thread {
        var err: String? = null
        for (p in ps) {
            try {
                uris.forEach { one(c, p, it) }
            } catch (e: Exception) {
                if (err == null) err = c.getString(R.string.error_fmt, "${p.name}: ${e.message ?: c.getString(R.string.err_connection)}")
            }
        }
        val msg = err ?: c.getString(R.string.sent)
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
        val afd = cr.openAssetFileDescriptor(u, "r") ?: error(c.getString(R.string.err_open))
        val len = afd.length
        if (len < 0) { afd.close(); throw IllegalArgumentException(c.getString(R.string.err_size)) }
        afd.createInputStream().use { ins ->
            Socket().use { s ->
                s.connect(InetSocketAddress(p.host, p.port), 10000)
                s.tcpNoDelay = true
                s.sendBufferSize = BUF
                s.soTimeout = 30000
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
                    if (pc != last) { last = pc; Bus.set(c.getString(R.string.sending, name), pc) }
                }
                out.flush()
                val l = BufferedReader(InputStreamReader(s.inputStream)).readLine()
                require(l?.contains(" 200") == true) { c.getString(R.string.err_rejected) }
            }
        }
    }
}
