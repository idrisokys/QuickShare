package io.qs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast

/** Kısayoldan çağrılır; arayüzsüzdür. QuickShare klasörünü dosya yöneticisinde açmayı sırayla dener. */
class FolderActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        open()
        finish()
    }

    private fun open() {
        val rel = if (SaveDir.direct()) "QuickShare" else "Download%2FQuickShare"
        val dir = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$rel")
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        val tries = ArrayList<Intent>()
        // Klasör gösteren dosya yöneticileri (File Manager+, Solid Explorer, MiXplorer, Material Files, DocumentsUI vb.)
        for (type in listOf("resource/folder", "vnd.android.document/directory", "inode/directory"))
            tries.add(Intent(Intent.ACTION_VIEW).setDataAndType(dir, type))
        // Olmadıysa dosya seçici (ACTION_GET_CONTENT) bu klasörde açılır
        tries.add(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, dir))
        for (i in tries) {
            try { startActivity(i.addFlags(flags)); return } catch (e: Exception) { }
        }
        Toast.makeText(this, SaveDir.dir().absolutePath, Toast.LENGTH_LONG).show()
    }
}
