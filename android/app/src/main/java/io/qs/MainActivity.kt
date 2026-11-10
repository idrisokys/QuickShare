package io.qs

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    class Row(val fp: String, val name: String)

    private lateinit var lv: ListView
    private lateinit var st: TextView
    private lateinit var bar: ProgressBar
    private var rows = listOf<Row>()
    private var target: String? = null   // satıra dokunulduysa dosyalar sadece o cihaza gider
    private var shortcutKey = ""

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
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = this@MainActivity.getString(R.string.ready, Identity.name)
            textSize = 24f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val infoBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_info)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = this@MainActivity.getString(R.string.info)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { showInfo(this) }
        }
        header.addView(infoBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)

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
                        text = r.name
                        setTextColor(Color.WHITE)
                    }
                }
            }
            setOnItemClickListener { _, _, i, _ ->
                target = rows[i].fp
                pickFiles()
            }
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        st = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { if (Bus.saved) openSavedFolder() }
        }
        root.addView(st)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)

        val btn = Button(this).apply {
            setText(R.string.select_file)
            setOnClickListener { target = null; pickFiles() }
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
        if (uris.isNotEmpty()) Sender.start(this, uris, target) { }
    }

    private fun refresh() {
        rows = Peers.list().map { Row(it.fp, it.name) }
        (lv.adapter as BaseAdapter).notifyDataSetChanged()
        val key = rows.joinToString { it.fp }
        if (key != shortcutKey) { shortcutKey = key; Shortcuts.update(this) }
        st.setTextColor(if (Bus.saved) 0xFF4FC3F7.toInt() else 0xFFBBBBBB.toInt())
        st.text = when {
            Bus.status.isNotEmpty() -> Bus.status
            rows.isEmpty() -> getString(R.string.searching)
            else -> getString(R.string.hint)
        }
        bar.visibility = if (Bus.pct >= 0) View.VISIBLE else View.INVISIBLE
        bar.progress = Bus.pct.coerceAtLeast(0)
    }

    private fun pickFiles() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 1
        )
    }

    /** Download/QuickShare klasörünün İÇİNİ dosya yöneticisinde açar (üst dizini değil). */
    private fun openSavedFolder() {
        val dir = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FQuickShare")
        // Önce sistemin klasör gösteren dosya yöneticilerini doğrudan hedefle; böylece "İndirilenler" yerine QuickShare açılır.
        for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui", null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(dir, "vnd.android.document/directory")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) i.setPackage(pkg)
                startActivity(i)
                return
            } catch (e: Exception) { }
        }
        try { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { }
    }

    /** Sağ üstteki bilgi (i) ikonu: küçük açılır kutu (resim, e-posta, GitHub linki). */
    private fun showInfo(anchor: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat() }
        }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.releases_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        box.addView(TextView(this).apply {
            text = "sislivadi@gmail.com"; textSize = 12f; setTextColor(Color.WHITE)
            setTextIsSelectable(true); setPadding(0, dp(4), 0, 0)
        })
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dp(8).toFloat()
        box.addView(TextView(this).apply {
            text = "https://github.com/idrisokys/"; textSize = 12f
            setTextColor(0xFF4FC3F7.toInt())
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(2), 0, 0)
            setOnClickListener {
                pw.dismiss()
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/idrisokys/"))) }
                catch (e: Exception) { toast(getString(R.string.link_fail)) }
            }
        })
        pw.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    override fun onStart() { super.onStart(); Bus.listener = { refresh() }; refresh() }
    override fun onStop() { Bus.listener = null; super.onStop() }
    override fun onDestroy() {
        if (isFinishing) stopService(Intent(this, ReceiverService::class.java))
        super.onDestroy()
    }
}
