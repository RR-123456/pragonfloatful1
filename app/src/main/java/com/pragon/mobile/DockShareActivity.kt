package com.pragon.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * v14: "Share > Pragon File Dock" from any app. Text, links, photos and files go into the File Dock (the sidebar's FILES section).
 * Invisible: it copies what was shared, says "Added to the File Dock", and closes. If the sidebar is on, it opens on the dock.
 */
class DockShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) { finish(); return }
        Thread {
            var added = 0
            var err: String? = null
            val ctx = applicationContext
            try {
                // links and text
                val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uris = streams(i)
                if (!text.isNullOrBlank() && uris.isEmpty()) { val e = Dock.addText(ctx, text); if (e == null) added++ else err = e }
                for (u in uris) {
                    try { val e = Dock.addUri(ctx, u, i.type?.takeIf { !it.endsWith("/*") }); if (e == null) added++ else err = e }
                    catch (ex: Exception) { err = "Could not read one of the files." }
                }
            } catch (ex: Exception) { err = "Could not add that to the File Dock." }
            runOnUiThread {
                try {
                    Toast.makeText(this, if (added > 0) "Added to the File Dock" else (err ?: "Nothing to add."), Toast.LENGTH_SHORT).show()
                    if (added > 0) {
                        if (SidebarService.running) SidebarService.showDock() else SidebarService.refreshFiles()
                    }
                } catch (ex: Exception) { }
                finish()   // always close: this window is invisible and must never be left on screen
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND) i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        return out
    }
}
