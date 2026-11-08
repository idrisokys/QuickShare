package io.qs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Sistem paylaş menüsünden gelen dosyaları güvenilir cihaza gönderir. */
class ShareActivity : Activity() {
    private var d: Discovery? = null

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Identity.init(this)
        val uris: List<Uri> =
            if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        if (uris.isEmpty()) { finish(); return }

        setContentView(TextView(this).apply { setText(R.string.share_searching); textSize = 18f; setPadding(48, 48, 48, 48) })
        d = Discovery(this).also { it.browse() }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) Sender.start(this, uris, intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)) { finish() }
        }, 1500)
    }

    override fun onDestroy() { d?.stop(); super.onDestroy() }
}
