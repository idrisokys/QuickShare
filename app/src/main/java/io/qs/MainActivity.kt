package io.qs

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    class Row(val fp: String, val name: String, val trusted: Boolean, val online: Boolean)

    private lateinit var lv: ListView
    private lateinit var st: TextView
    private lateinit var bar: ProgressBar
    private var rows = listOf<Row>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Identity.init(this)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xFF111111.toInt())
        }
        root.addView(TextView(this).apply {
            text = Identity.name + "  ·  hazır"
            textSize = 24f
            setTextColor(Color.WHITE)
        })

        lv = ListView(this).apply {
            divider = null
            selector = ColorDrawable(0x334FC3F7)
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(i: Int) = rows[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                    val r = rows[i]
                    return ((v as? TextView) ?: TextView(this@MainActivity)).apply {
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        textSize = 20f
                        text = (if (r.trusted) "✓ " else "") + r.name + (if (r.online) "" else "  (çevrimdışı)")
                        setTextColor(if (r.online) Color.WHITE else 0xFF888888.toInt())
                    }
                }
            }
            setOnItemClickListener { _, _, i, _ ->
                val r = rows[i]
                if (!r.trusted) {
                    Trust.add(r.fp, r.name); Shortcuts.update(this@MainActivity)
                    toast("Güvenilir cihaz eklendi"); refresh()
                }
            }
            setOnItemLongClickListener { _, _, i, _ ->
                val r = rows[i]
                if (r.trusted) {
                    Trust.del(r.fp); Shortcuts.update(this@MainActivity)
                    toast("Listeden çıkarıldı"); refresh()
                }
                true
            }
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        st = TextView(this).apply { textSize = 16f; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, dp(8), 0, dp(8)) }
        root.addView(st)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)

        val btn = Button(this).apply {
            text = "Dosya seç"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1
                )
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)
        btn.requestFocus()

        startForegroundService(Intent(this, ReceiverService::class.java))
        Shortcuts.update(this)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc != 1 || res != RESULT_OK || d == null) return
        val uris = mutableListOf<Uri>()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        d.data?.let { if (uris.isEmpty()) uris += it }
        if (uris.isNotEmpty()) Sender.start(this, uris, null) { }
    }

    private fun refresh() {
        val ps = Peers.list()
        val t = Trust.all()
        rows = ps.map { Row(it.fp, it.name, t.containsKey(it.fp), true) } +
            t.filter { (k, _) -> ps.none { it.fp == k } }.map { Row(it.key, it.value, true, false) }
        (lv.adapter as BaseAdapter).notifyDataSetChanged()
        st.text = when {
            Bus.status.isNotEmpty() -> Bus.status
            rows.isEmpty() -> "Yakındaki cihazlar aranıyor…"
            else -> "Eklemek için dokun, silmek için uzun bas"
        }
        bar.visibility = if (Bus.pct >= 0) View.VISIBLE else View.INVISIBLE
        bar.progress = Bus.pct.coerceAtLeast(0)
    }

    override fun onStart() { super.onStart(); Bus.listener = { refresh() }; refresh() }
    override fun onStop() { Bus.listener = null; super.onStop() }
    override fun onDestroy() {
        if (isFinishing) stopService(Intent(this, ReceiverService::class.java))
        super.onDestroy()
    }
}
