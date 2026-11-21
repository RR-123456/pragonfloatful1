package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * v14: My Files (assets/files.html). A file manager inside Pragon: storage bar, Photos / Videos / Audio / Documents / APKs / Archives,
 * Sources, Quick access folders, Tags, Recent, search, and Recently deleted (a 30-day bin, not an instant delete).
 * Needs "All files access" (Android asks once; the page has a button for it). Nothing here uses the network.
 */
class FilesActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val root: File = Environment.getExternalStorageDirectory()
    private val trashDir get() = File(root, ".PragonTrash")
    private val sp by lazy { getSharedPreferences("pragon_files", MODE_PRIVATE) }
    private val thumbs = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val VID = setOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v")
    private val DOCS = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp", "epub", "md", "json", "xml", "html")
    private val ARCH = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso")
    private val AUD = setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus", "amr")
    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                // Thumbnails of photos and videos: the page asks for https://pragon-thumb.local/t?p=<path>
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url ?: return null
                    if (u.host != "pragon-thumb.local") return null
                    val p = u.getQueryParameter("p") ?: return null
                    val bytes = thumb(p)
                        ?: return WebResourceResponse("image/png", null, 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400"), ByteArrayInputStream(bytes))
                }
            }
            addJavascriptInterface(Bridge(), "PragonFiles")
            loadUrl("file:///android_asset/files.html")
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // the page closes its own sheet / folder / selection first; when it has nothing left to close we leave
                web.evaluateJavascript("(window.fmBack&&fmBack())?true:false") { r -> if (r != "true") finish() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.fmResume&&fmResume()", null)
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun hasAll(): Boolean = Environment.isExternalStorageManager()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun inRoot(f: File): Boolean = try { f.canonicalPath.startsWith(root.canonicalPath) } catch (_: Exception) { false }

    private fun fj(f: File): JSONObject {
        val o = JSONObject().put("n", f.name).put("p", f.absolutePath).put("d", f.isDirectory)
            .put("s", if (f.isFile) f.length() else 0L).put("m", f.lastModified() / 1000)
        if (f.isDirectory) o.put("c", f.list()?.count { !it.startsWith(".") } ?: 0)
        return o
    }

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun scan(vararg paths: String) {
        try { MediaScannerConnection.scanFile(this, paths, null, null) } catch (_: Exception) { }
    }

    private fun thumb(p: String): ByteArray? {
        thumbs.get(p)?.let { return it }
        val f = File(p)
        if (!f.isFile || !inRoot(f)) return null
        val ext = f.extension.lowercase()
        val bmp: Bitmap? = try {
            when (ext) {
                in IMG -> ThumbnailUtils.createImageThumbnail(f, Size(256, 256), null)
                in VID -> ThumbnailUtils.createVideoThumbnail(f, Size(256, 256), null)
                else -> null
            }
        } catch (_: Throwable) { null }
        if (bmp == null) return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b = out.toByteArray()
        thumbs.put(p, b)
        return b
    }

    // MediaStore: counts and lists for the categories (photos, videos, ...), recent files and search
    private fun extCond(exts: List<String>): Pair<String, List<String>> =
        "(" + exts.joinToString(" OR ") { "_display_name LIKE ?" } + ")" to exts.map { "%.$it" }

    private fun catCond(cat: String): Pair<String, List<String>> {
        val (w, a) = when (cat) {
            "photos" -> "media_type=1" to emptyList()
            "videos" -> "media_type=3" to emptyList()
            "audio" -> "media_type=2" to emptyList()
            "docs" -> extCond(DOCS)
            "apks" -> extCond(listOf("apk"))
            "archives" -> extCond(ARCH)
            "recent" -> "(_size>0 AND mime_type IS NOT NULL AND _data NOT LIKE ?)" to listOf("%/Android/%")
            else -> "1=1" to emptyList()
        }
        return "$w AND _data NOT LIKE ?" to (a + "%/.%")      // hidden folders and the bin never show up here
    }

    private fun mq(where: String, args: List<String>, offset: Int, limit: Int, countOnly: Boolean = false): Any {
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            if (!countOnly) {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf("date_modified"))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
        }
        val proj = if (countOnly) arrayOf("_id") else arrayOf("_data", "_display_name", "_size", "date_modified")
        val arr = JSONArray()
        var n = 0
        try {
            contentResolver.query(FILES_URI, proj, b, null)?.use { c ->
                if (countOnly) { n = c.count; return@use }
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    arr.put(JSONObject().put("n", c.getString(1) ?: File(path).name).put("p", path).put("d", false)
                        .put("s", c.getLong(2)).put("m", c.getLong(3)))
                }
            }
        } catch (_: Exception) { }
        return if (countOnly) n else arr
    }

    // ───────────────────────── tags, quick access, sources, bin (stored here, nothing leaves the phone) ─────────────────────────

    private fun tagsObj(): JSONObject {
        val s = sp.getString("tags", null)
        if (s != null) return try { JSONObject(s) } catch (_: Exception) { JSONObject() }
        val o = JSONObject()
        val order = JSONArray()
        for (t in listOf("project", "important", "work", "study", "life")) { o.put(t, JSONArray()); order.put(t) }
        sp.edit().putString("tags", o.toString()).putString("tag_order", order.toString()).apply()
        return o
    }

    private fun tagOrder(): List<String> {
        val t = tagsObj()
        val a = try { JSONArray(sp.getString("tag_order", "[]")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<String>()
        for (i in 0 until a.length()) if (t.has(a.getString(i))) out.add(a.getString(i))
        for (k in t.keys()) if (k !in out) out.add(k)
        return out
    }

    private fun saveTags(t: JSONObject, order: List<String>? = null) {
        val e = sp.edit().putString("tags", t.toString())
        if (order != null) e.putString("tag_order", JSONArray(order).toString())
        e.apply()
    }

    private fun tagPaths(t: JSONObject, name: String): List<String> {
        val a = t.optJSONArray(name) ?: return emptyList()
        val l = ArrayList<String>()
        for (i in 0 until a.length()) l.add(a.getString(i))
        return l
    }

    private fun quickList(): MutableList<String> {
        val a = try { JSONArray(sp.getString("quick", "[]")) } catch (_: Exception) { JSONArray() }
        return MutableList(a.length()) { a.getString(it) }
    }
    private fun saveQuick(l: List<String>) = sp.edit().putString("quick", JSONArray(l).toString()).apply()

    private fun customSources(): MutableList<String> {
        val a = try { JSONArray(sp.getString("sources", "[]")) } catch (_: Exception) { JSONArray() }
        return MutableList(a.length()) { a.getString(it) }
    }
    private fun saveSources(l: List<String>) = sp.edit().putString("sources", JSONArray(l).toString()).apply()

    /** A file or folder was renamed or moved: tags and quick access follow it. */
    private fun retarget(old: String, new: String) {
        val t = tagsObj(); var ch = false
        for (name in t.keys().asSequence().toList()) {
            val a = t.getJSONArray(name)
            for (i in 0 until a.length()) {
                val p = a.getString(i)
                if (p == old || p.startsWith("$old/")) { a.put(i, new + p.substring(old.length)); ch = true }
            }
        }
        if (ch) saveTags(t)
        val q = quickList()
        var qc = false
        for (i in q.indices) if (q[i] == old || q[i].startsWith("$old/")) { q[i] = new + q[i].substring(old.length); qc = true }
        if (qc) saveQuick(q)
    }

    private fun trashMeta(): JSONObject = try { JSONObject(sp.getString("trash", "{}")) } catch (_: Exception) { JSONObject() }

    private fun purgeTrash() {
        val m = trashMeta(); var ch = false
        val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        for (id in m.keys().asSequence().toList()) {
            val e = m.optJSONObject(id) ?: continue
            val f = File(trashDir, id)
            if (!f.exists()) { m.remove(id); ch = true }
            else if (e.optLong("ts") < limit) { f.deleteRecursively(); m.remove(id); ch = true }
        }
        if (ch) sp.edit().putString("trash", m.toString()).apply()
    }

    private fun toTrash(path: String): Boolean {
        val f = File(path)
        if (!f.exists() || !inRoot(f) || f.absolutePath == root.absolutePath) return false
        trashDir.mkdirs()
        val id = "${System.currentTimeMillis()}_${f.name}"
        val dest = File(trashDir, id)
        val ok = f.renameTo(dest) || (try { f.copyRecursively(dest, true) && f.deleteRecursively() } catch (_: Exception) { false })
        if (ok) {
            val m = trashMeta()
            m.put(id, JSONObject().put("orig", f.absolutePath).put("ts", System.currentTimeMillis()))
            sp.edit().putString("trash", m.toString()).apply()
            scan(path)
        }
        return ok
    }

    private fun freeName(f: File): File {
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var i = 1
        while (true) { val c = File(f.parentFile, "$base ($i)$ext"); if (!c.exists()) return c; i++ }
    }

    private fun marketingGb(totalBytes: Long): Long {
        val gb = totalBytes / 1_000_000_000.0
        for (s in longArrayOf(16, 32, 64, 128, 256, 512, 1024, 2048)) if (s >= gb) return s * 1_000_000_000L
        return totalBytes
    }


    // ───────────────────────── v17: whole-category lists, big deletes, duplicate finder ─────────────────────────

    /** Every row of a category in one go (the page groups photos into albums and sorts them, so it needs the lot). */
    private fun mqAll(where: String, args: List<String>): JSONArray {
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf("date_modified"))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val arr = JSONArray()
        try {
            contentResolver.query(FILES_URI, arrayOf("_data", "_display_name", "_size", "date_modified"), b, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    arr.put(JSONObject().put("n", c.getString(1) ?: File(path).name).put("p", path).put("d", false)
                        .put("s", c.getLong(2)).put("m", c.getLong(3)))
                }
            }
        } catch (_: Exception) { }
        return arr
    }

    private fun js(code: String) { runOnUiThread { try { web.evaluateJavascript(code, null) } catch (_: Exception) { } } }

    private fun parsePaths(json: String): List<String> {
        val a = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    /**
     * Moves many files to the bin in one pass. Built for 1000+ at a time: the bin list is read once and saved every 200 files
     * (not once per file), every bin name is unique even when 1000 files are moved in the same millisecond, and the media
     * scanner is told about all of them in a single call. [tick] reports progress.
     */
    private fun trashMany(paths: List<String>, tick: (Int, Int) -> Unit): Pair<Int, Int> {
        trashDir.mkdirs()
        val m = trashMeta()
        val t0 = System.currentTimeMillis()
        var ok = 0; var fail = 0; var seq = 0
        val moved = ArrayList<String>()
        for ((i, path) in paths.withIndex()) {
            val f = File(path)
            if (!f.exists() || !inRoot(f) || f.absolutePath == root.absolutePath) {
                fail++
            } else {
                val id = "${t0}_${seq++}_${f.name.takeLast(120)}"
                val dest = File(trashDir, id)
                val done = f.renameTo(dest) || (try { f.copyRecursively(dest, true) && f.deleteRecursively() } catch (_: Exception) { false })
                if (done) {
                    m.put(id, JSONObject().put("orig", f.absolutePath).put("ts", System.currentTimeMillis()))
                    ok++; moved.add(path)
                } else fail++
            }
            if ((i + 1) % 200 == 0) sp.edit().putString("trash", m.toString()).apply()      // a crash halfway keeps what was already moved
            if ((i + 1) % 25 == 0 || i == paths.size - 1) tick(i + 1, paths.size)
        }
        sp.edit().putString("trash", m.toString()).apply()
        if (moved.isNotEmpty()) scan(*moved.toTypedArray())
        return ok to fail
    }

    @Volatile private var dupCancel = false
    private var dupJob: Thread? = null

    private fun catOf(f: File): String {
        val e = f.extension.lowercase()
        return when {
            e in IMG -> "photos"; e in VID -> "videos"; e in AUD -> "audio"; e in DOCS -> "docs"
            e == "apk" -> "apks"; e in ARCH -> "archives"; else -> ""
        }
    }

    /** SHA-1 of the whole file, or of its first and last 256 KB when [partial] (a cheap first filter). Null = unreadable or cancelled. */
    private fun hashFile(f: File, partial: Boolean): String? {
        try {
            val md = MessageDigest.getInstance("SHA-1")
            FileInputStream(f).use { ins ->
                val buf = ByteArray(256 * 1024)
                if (partial) {
                    var n = ins.read(buf); if (n > 0) md.update(buf, 0, n)
                    val len = f.length()
                    if (len > 512 * 1024) { ins.channel.position(len - 256 * 1024); n = ins.read(buf); if (n > 0) md.update(buf, 0, n) }
                } else {
                    while (true) {
                        if (dupCancel) return null
                        val n = ins.read(buf); if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { return null }
    }

    /** Exact duplicates among [files]: same size, then same start/end, then same SHA-1 of the whole file. Reports progress to the page. */
    private fun findDups(files: List<File>): JSONArray {
        val bySize = files.filter { it.isFile && it.length() > 0 }.groupBy { it.length() }.filter { it.value.size > 1 }
        val total = bySize.values.sumOf { it.size } * 2
        var done = 0
        fun tick() { done++; if (done % 20 == 0 || done == total) js("fmDupProgress($done,$total)") }
        val groups = ArrayList<List<File>>()
        for ((_, same) in bySize) {
            if (dupCancel) break
            val part = HashMap<String, MutableList<File>>()
            for (f in same) { hashFile(f, true)?.let { part.getOrPut(it) { ArrayList() }.add(f) }; tick() }
            for ((_, cand) in part) {
                if (cand.size < 2) { done += cand.size; continue }
                val full = HashMap<String, MutableList<File>>()
                for (f in cand) { if (dupCancel) break; hashFile(f, false)?.let { full.getOrPut(it) { ArrayList() }.add(f) }; tick() }
                for ((_, g) in full) if (g.size > 1) groups.add(g.sortedBy { it.lastModified() })    // oldest first = the one to keep
            }
        }
        groups.sortByDescending { it[0].length() * (it.size - 1) }
        val out = JSONArray()
        for (g in groups.take(500)) { val a = JSONArray(); for (f in g) a.put(fj(f)); out.put(a) }
        return out
    }

    private fun runDup(work: () -> JSONArray) {
        dupCancel = false
        dupJob = Thread {
            val r = try { work() } catch (_: Throwable) { JSONArray() }
            js("fmDupDone(" + JSONObject().put("groups", r).put("cancelled", dupCancel).toString() + ")")
        }.also { it.start() }
    }

    // ───────────────────────── the bridge the page calls ─────────────────────────

    inner class Bridge {
        @JavascriptInterface fun perm(): String = hasAll().toString()

        @JavascriptInterface fun askPerm() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) { toast("Open Settings > Apps > Pragon > Files and media and allow all files.") }
                }
            }
        }

        @JavascriptInterface fun close() { runOnUiThread { finish() } }

        @JavascriptInterface fun home(): String {
            purgeTrash()
            val st = StatFs(root.path)
            return JSONObject().put("perm", hasAll())
                .put("total", marketingGb(st.totalBytes)).put("used", st.totalBytes - st.availableBytes)
                .put("root", root.absolutePath).put("trash", trashMeta().length()).toString()
        }

        @JavascriptInterface fun counts(): String {
            val o = JSONObject()
            for (c in listOf("photos", "videos", "audio", "docs", "apks", "archives")) {
                val (w, a) = catCond(c)
                o.put(c, mq(w, a, 0, 0, true) as Int)
            }
            return o.toString()
        }

        @JavascriptInterface fun sources(): String {
            val a = JSONArray()
            a.put(JSONObject().put("n", "Internal storage").put("p", root.absolutePath).put("k", "root"))
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            a.put(JSONObject().put("n", "Downloads").put("p", dl.absolutePath).put("k", "dl"))
            val wa = listOf("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp/Media").map { File(root, it) }.firstOrNull { it.isDirectory }
            if (wa != null) a.put(JSONObject().put("n", "WhatsApp").put("p", wa.absolutePath).put("k", "wa"))
            val bt = listOf("Download/Bluetooth", "Bluetooth").map { File(root, it) }.firstOrNull { it.isDirectory }
            if (bt != null) a.put(JSONObject().put("n", "Bluetooth").put("p", bt.absolutePath).put("k", "bt"))
            for (p in customSources()) if (File(p).isDirectory) a.put(JSONObject().put("n", File(p).name).put("p", p).put("k", "custom"))
            return a.toString()
        }

        @JavascriptInterface fun addSource(path: String) {
            val l = customSources(); if (path !in l) { l.add(path); saveSources(l) }
        }
        @JavascriptInterface fun removeSource(path: String) { saveSources(customSources().filter { it != path }) }

        @JavascriptInterface fun quick(): String {
            val a = JSONArray()
            for (p in quickList()) { val f = File(p); if (f.isDirectory) a.put(JSONObject().put("n", f.name).put("p", p)) }
            return a.toString()
        }
        @JavascriptInterface fun addQuick(path: String) {
            val l = quickList(); if (path !in l) { l.add(path); saveQuick(l) }
        }
        @JavascriptInterface fun removeQuick(path: String) { saveQuick(quickList().filter { it != path }) }

        /** A folder's contents. sort: name | date | size | type */
        @JavascriptInterface fun list(path: String, sort: String): String {
            val dir = File(path)
            val o = JSONObject().put("path", path)
            if (!dir.isDirectory || !inRoot(dir) || !dir.canRead()) return o.put("ok", false).put("items", JSONArray()).toString()
            val kids = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
            val cmp: Comparator<File> = when (sort) {
                "date" -> compareByDescending<File> { it.lastModified() }
                "size" -> compareByDescending<File> { if (it.isFile) it.length() else -1L }
                "type" -> compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
            val sorted = kids.sortedWith(compareByDescending<File> { it.isDirectory }.then(cmp))
            val a = JSONArray(); for (f in sorted) a.put(fj(f))
            return o.put("ok", true).put("items", a).toString()
        }

        @JavascriptInterface fun category(cat: String, offset: Int): String {
            val (w, a) = catCond(cat)
            return mq(w, a, offset, 200).toString()
        }

        @JavascriptInterface fun recent(offset: Int): String {
            val (w, a) = catCond("recent")
            return mq(w, a, offset, 200).toString()
        }

        @JavascriptInterface fun search(q: String, offset: Int): String {
            val t = q.trim()
            if (t.isEmpty()) return "[]"
            val (w, a) = catCond("any")
            return mq("_display_name LIKE ? AND $w", listOf("%$t%") + a, offset, 200).toString()
        }

        // ── tags ──
        @JavascriptInterface fun tags(): String {
            val t = tagsObj(); val a = JSONArray()
            for (n in tagOrder()) a.put(JSONObject().put("n", n).put("c", tagPaths(t, n).count { File(it).exists() }))
            return a.toString()
        }
        @JavascriptInterface fun addTag(name: String): String {
            val n = name.trim().take(24)
            if (n.isEmpty()) return "false"
            val t = tagsObj()
            if (t.has(n)) return "false"
            t.put(n, JSONArray()); saveTags(t, tagOrder() + n)
            return "true"
        }
        @JavascriptInterface fun deleteTag(name: String) {
            val t = tagsObj(); t.remove(name); saveTags(t, tagOrder().filter { it != name })
        }
        @JavascriptInterface fun tagFiles(name: String): String {
            val a = JSONArray()
            for (p in tagPaths(tagsObj(), name)) { val f = File(p); if (f.exists()) a.put(fj(f)) }
            return a.toString()
        }
        @JavascriptInterface fun fileTags(path: String): String {
            val t = tagsObj(); val a = JSONArray()
            for (n in tagOrder()) if (path in tagPaths(t, n)) a.put(n)
            return a.toString()
        }
        /** Tags or un-tags many files with one read and one write (1000 files at once). */
        @JavascriptInterface fun setTagMany(paths: String, name: String, on: Boolean) {
            val t = tagsObj(); val cur = LinkedHashSet(tagPaths(t, name)); val ps = parsePaths(paths)
            if (on) cur.addAll(ps) else cur.removeAll(ps.toSet())
            t.put(name, JSONArray(cur.toList())); saveTags(t)
        }
        @JavascriptInterface fun setTag(path: String, name: String, on: Boolean) {
            val t = tagsObj()
            val cur = tagPaths(t, name).filter { it != path }.toMutableList()
            if (on) cur.add(path)
            t.put(name, JSONArray(cur)); saveTags(t)
        }

        // ── actions on files ──
        @JavascriptInterface fun open(path: String) {
            val f = File(path)
            if (!f.isFile || !inRoot(f)) { toast("That file is gone."); return }
            Dock.touch(this@FilesActivity, f.absolutePath)      // opening a file often puts it in Quick files by itself
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { toast("No app on this phone can open that file.") }
            }
        }
        @JavascriptInterface fun share(path: String) {
            val f = File(path)
            if (!f.isFile || !inRoot(f)) { toast("Only files can be shared, not folders."); return }
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uriFor(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Share"))
                } catch (_: Exception) { toast("Could not share that file.") }
            }
        }
        /** v14: File Dock and Quick files (sidebar) */
        @JavascriptInterface fun addDock(path: String): String {
            val f = File(path)
            if (!f.isFile || !inRoot(f)) return "Only files can go in the dock."
            val e = Dock.addFile(this@FilesActivity, f)
            if (e == null) SidebarService.refreshFiles()
            return e ?: ""
        }
        @JavascriptInterface fun isPinned(path: String): String = Dock.isPinned(this@FilesActivity, path).toString()
        @JavascriptInterface fun pinFile(path: String, on: Boolean): String {
            if (!File(path).isFile) return "Only files can be pinned."
            Dock.pin(this@FilesActivity, path, on); SidebarService.refreshFiles(); return ""
        }
        @JavascriptInterface fun copyText(text: String) {
            runOnUiThread {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", text))
                toast("Copied")
            }
        }
        @JavascriptInterface fun rename(path: String, newName: String): String {
            val f = File(path); val n = newName.trim()
            if (n.isEmpty() || n.contains('/') || n.startsWith(".") || !f.exists() || !inRoot(f)) return "Pick a different name."
            val dest = File(f.parentFile, n)
            if (dest.exists()) return "Something with that name already exists."
            if (!f.renameTo(dest)) return "Android would not rename it."
            retarget(f.absolutePath, dest.absolutePath); scan(f.absolutePath, dest.absolutePath)
            return ""
        }
        @JavascriptInterface fun mkdir(parent: String, name: String): String {
            val n = name.trim(); val p = File(parent)
            if (n.isEmpty() || n.contains('/') || n.startsWith(".") || !inRoot(p)) return "Pick a different name."
            val d = File(p, n)
            if (d.exists()) return "That folder already exists."
            return if (d.mkdirs()) "" else "Android would not create it."
        }
        /** Moves to the bin (Recently deleted), kept 30 days. Returns how many were moved. */
        @JavascriptInterface fun trash(paths: String): String {
            return trashMany(parsePaths(paths)) { _, _ -> }.first.toString()
        }
        @JavascriptInterface fun info(path: String): String {
            val f = File(path)
            if (!f.exists()) return "{}"
            val o = fj(f).put("mime", if (f.isFile) mimeOf(f) else "folder")
            if (f.isDirectory) {
                var size = 0L; var files = 0
                for (x in f.walkTopDown().take(20000)) if (x.isFile) { size += x.length(); files++ }
                o.put("s", size).put("files", files)
            }
            return o.toString()
        }


        // ── v17: whole category, big deletes, duplicates ──
        @JavascriptInterface fun library(cat: String): String {
            val (w, a) = catCond(cat)
            return mqAll(w, a).toString()
        }
        /** Moves any number of files to the bin in the background; the page gets fmProgress(done,total) and fmDone('trash',ok,fail). */
        @JavascriptInterface fun trashAsync(paths: String) {
            val list = parsePaths(paths)
            Thread {
                val (ok, fail) = try { trashMany(list) { d, t -> js("fmProgress($d,$t)") } } catch (_: Throwable) { 0 to list.size }
                js("fmDone('trash',$ok,$fail)")
            }.start()
        }
        /** Finds exact duplicates inside one category (photos, videos, audio, docs, apks or archives). */
        @JavascriptInterface fun dupScan(cat: String) {
            val (w, a) = catCond(cat)
            runDup { findDups(mqAll(w, a).let { arr -> (0 until arr.length()).map { File(arr.getJSONObject(it).getString("p")) } }) }
        }
        /** Finds the duplicates of one file (same kind of file, same size, same content). */
        @JavascriptInterface fun dupOf(path: String) {
            val f = File(path)
            runDup {
                val cat = catOf(f)
                if (!f.isFile || cat.isEmpty()) JSONArray() else {
                    val (w, a) = catCond(cat)
                    val arr = mqAll(w, a)
                    val same = ArrayList<File>()
                    for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getLong("s") == f.length()) same.add(File(o.getString("p"))) }
                    if (same.none { it.absolutePath == f.absolutePath }) same.add(f)
                    findDups(same).let { all ->
                        // only the group that contains this file
                        val out = JSONArray()
                        for (i in 0 until all.length()) { val g = all.getJSONArray(i); for (j in 0 until g.length()) if (g.getJSONObject(j).getString("p") == f.absolutePath) { out.put(g); break } }
                        out
                    }
                }
            }
        }
        @JavascriptInterface fun dupCancel() { dupCancel = true }

        // ── Recently deleted ──
        @JavascriptInterface fun trashList(): String {
            purgeTrash()
            val m = trashMeta(); val a = JSONArray()
            for (id in m.keys().asSequence().toList()) {
                val e = m.optJSONObject(id) ?: continue; val f = File(trashDir, id)
                if (!f.exists()) continue
                val o = fj(f).put("n", File(e.optString("orig")).name).put("id", id).put("orig", e.optString("orig")).put("ts", e.optLong("ts") / 1000)
                a.put(o)
            }
            return a.toString()
        }
        @JavascriptInterface fun restore(id: String): String {
            val m = trashMeta(); val e = m.optJSONObject(id) ?: return "Not found."
            val f = File(trashDir, id)
            if (!f.exists()) return "Not found."
            val orig = File(e.optString("orig"))
            orig.parentFile?.mkdirs()
            val dest = freeName(orig)
            val ok = f.renameTo(dest) || (try { f.copyRecursively(dest, true) && f.deleteRecursively() } catch (_: Exception) { false })
            if (!ok) return "Could not restore it."
            m.remove(id); sp.edit().putString("trash", m.toString()).apply()
            if (dest.absolutePath != orig.absolutePath) retarget(orig.absolutePath, dest.absolutePath)
            scan(dest.absolutePath)
            return ""
        }
        @JavascriptInterface fun deleteForever(id: String) {
            val m = trashMeta()
            File(trashDir, id).deleteRecursively()
            m.remove(id); sp.edit().putString("trash", m.toString()).apply()
        }
        @JavascriptInterface fun emptyTrash() {
            trashDir.deleteRecursively()
            sp.edit().putString("trash", "{}").apply()
        }
    }
}
