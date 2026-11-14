package io.qs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Bildirimdeki cihaz butonundan açılır: dosya seçtirir ve dosyaları SADECE o cihaza ("to" = cihaz kimliği) gönderir.
 * Arayüzü yoktur (şeffaf); seçim bitince kapanır, aktarım bildirimde ilerler.
 */
class PickActivity : Activity() {
    private var to: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Identity.init(this)
        to = intent.getStringExtra("to")
        if (b != null) return   // ekran döndürme vb.: seçici zaten açık
        if (Peers.list().none { it.id == to }) { toast(R.string.no_device); finish(); return }
        try {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1
            )
        } catch (e: ActivityNotFoundException) { toast(R.string.fm_missing); finish() }
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc == 1 && res == RESULT_OK && d != null) {
            val uris = mutableListOf<Uri>()
            d.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
            d.data?.let { if (uris.isEmpty()) uris += it }
            if (uris.isNotEmpty()) {
                if (Peers.list().none { it.id == to }) toast(R.string.no_device)   // seçim sırasında cihaz kapanmış
                else Sender.submit(this, uris, to)
            }
        }
        finish()
    }

    private fun toast(r: Int) = Toast.makeText(this, r, Toast.LENGTH_LONG).show()
}
