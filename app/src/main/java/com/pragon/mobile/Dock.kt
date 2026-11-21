package com.pragon.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v14: the data behind the sidebar's FILES section.
 *   FILE DOCK   a temporary backpack: text, links, photos and files you put in (Share > Pragon File Dock, Paste, or a drop) and drag out into another app.
 *   DOC BOX     the files you downloaded lately (and documents, archives, APKs anywhere), sorted by date and time, filtered by type (PDF / DOC / XLS ...).
 *   QUICK FILES files you pin, plus files you open often (3 or more times through Pragon) pick themselves.
 * Every list is newest first by default; each has its own sort (date, name, size, type; tap the same one again to flip the direction).
 * Nothing leaves the phone. Dock copies live in the app's private folder and are removed when you remove them.
 */
object Dock {

    data class FEntry(
        val id: String, val name: String, val path: String?, val text: String?,
        val kind: String,            // text | link | image | file
        val size: Long, val time: Long, val ext: String, val opens: Int = 0, val pinned: Boolean = false,
    )
    data class Sort(val key: String, val asc: Boolean)

    const val MAX_ITEMS = 40
    const val MAX_FILE = 150L * 1024 * 1024
    val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

    /** Doc box filters: key -> label, and the extensions each one means. */
    val GROUPS = linkedMapOf("all" to "All", "pdf" to "PDF", "doc" to "DOC", "xls" to "XLS", "ppt" to "PPT", "txt" to "TXT", "zip" to "ZIP", "apk" to "APK")
    val EXT: Map<String, List<String>> = mapOf(
        "pdf" to listOf("pdf"),
        "doc" to listOf("doc", "docx", "odt", "rtf"),
        "xls" to listOf("xls", "xlsx", "csv", "ods"),
        "ppt" to listOf("ppt", "pptx", "odp"),
        "txt" to listOf("txt", "md", "json", "xml", "html", "epub"),
        "zip" to listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso"),
        "apk" to listOf("apk"),
    )

    private fun sp(c: Context) = c.getSharedPreferences("pragon_dock", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.filesDir, "dock").also { it.mkdirs() }
    fun hasFileAccess(): Boolean = Environment.isExternalStorageManager()
    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36) + (Math.random() * 1000).toInt()

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    // ─────────────── sorting (every list has its own, saved) ───────────────

    fun sort(c: Context, list: String): Sort {
        val s = (sp(c).getString("sort_$list", "date:desc") ?: "date:desc").split(":")
        return Sort(s.getOrNull(0) ?: "date", (s.getOrNull(1) ?: "desc") == "asc")
    }

    /** Tapping the chip that is already on flips the direction; another chip starts with its natural direction. */
    fun setSort(c: Context, list: String, key: String): Sort {
        val cur = sort(c, list)
        val next = if (cur.key == key) Sort(key, !cur.asc) else Sort(key, key == "name" || key == "type")
        sp(c).edit().putString("sort_$list", next.key + ":" + (if (next.asc) "asc" else "desc")).apply()
        return next
    }

    fun sorted(items: List<FEntry>, s: Sort): List<FEntry> {
        val ci = String.CASE_INSENSITIVE_ORDER
        val cmp: Comparator<FEntry> = when (s.key) {
            "name" -> compareBy<FEntry, String>(ci) { it.name }
            "size" -> compareBy<FEntry> { it.size }.thenBy(ci) { it.name }
            "type" -> compareBy<FEntry, String>(ci) { it.ext }.thenBy(ci) { it.name }
            else -> compareBy<FEntry> { it.time }
        }
        return items.sortedWith(if (s.asc) cmp else cmp.reversed())
    }

    // ─────────────── the dock ───────────────

    private fun load(c: Context): JSONArray = try { JSONArray(sp(c).getString("dock", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = sp(c).edit().putString("dock", a.toString()).apply()

    fun items(c: Context): List<FEntry> {
        val a = load(c); val out = ArrayList<FEntry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val path = o.optString("path").ifBlank { null }
            if (path != null && !File(path).exists()) continue
            val name = o.optString("name")
            out.add(FEntry(o.optString("id"), name, path, o.optString("text").ifBlank { null }, o.optString("k", "file"),
                o.optLong("size"), o.optLong("t"), if (o.optString("k") == "link") "url" else if (o.optString("k") == "text") "txt" else extOf(name)))
        }
        return out
    }

    private fun trim(c: Context, a: JSONArray): JSONArray {
        // oldest first inside the array; when full, the oldest go (their copied files too)
        while (a.length() > MAX_ITEMS) {
            val o = a.optJSONObject(0)
            o?.optString("path")?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            a.remove(0)
        }
        return a
    }

    /** Adds text or a link. Returns an error message, or null when it worked. */
    fun addText(c: Context, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "Nothing to add."
        val body = t.take(20000)
        val a = load(c)
        for (i in a.length() - 1 downTo 0) if (a.optJSONObject(i)?.optString("text") == body) a.remove(i)   // the same text moves to the top
        val link = Regex("^https?://\\S+$").matches(body)
        a.put(JSONObject().put("id", newId()).put("k", if (link) "link" else "text").put("name", body.lineSequence().first().take(60))
            .put("text", body).put("size", body.length.toLong()).put("t", System.currentTimeMillis()))
        save(c, trim(c, a))
        return null
    }

    /** Copies a file given by a content:// or file:// address into the dock. */
    fun addUri(c: Context, uri: Uri, hintMime: String? = null): String? {
        val cr = c.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { n -> name = n }
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        } catch (_: Exception) { }
        if (size > MAX_FILE) return "That file is bigger than 150 MB."
        val mime = hintMime ?: try { cr.getType(uri) } catch (_: Exception) { null }
        if (extOf(name).isEmpty() && mime != null) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        val id = newId()
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val dest = File(dir(c), "${id}_$safe")
        try {
            (cr.openInputStream(uri) ?: return "Could not read that file.").use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: SecurityException) { dest.delete(); return "That app doesn't allow it. Use Share > Pragon File Dock there instead." }
        catch (e: Exception) { dest.delete(); return "Could not copy it." }
        val img = mime?.startsWith("image/") == true || extOf(name) in IMG
        val a = load(c)
        a.put(JSONObject().put("id", id).put("k", if (img) "image" else "file").put("name", name).put("path", dest.absolutePath)
            .put("size", dest.length()).put("t", System.currentTimeMillis()))
        save(c, trim(c, a))
        return null
    }

    fun addFile(c: Context, f: File): String? =
        if (!f.isFile) "Only files can go in the dock." else addUri(c, Uri.fromFile(f))

    /** Reads what is on the clipboard. Works while the accessibility service is on (Android hides the clipboard from background apps otherwise). */
    fun addFromClipboard(c: Context): String? {
        val ctx = PragonAccessibilityService.instance ?: c
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (_: Exception) { null }
        if (clip == null || clip.itemCount == 0) return "The clipboard looks empty here. Copy something first (Android only lets Pragon read it while Pragon's accessibility is on)."
        var added = 0; var err: String? = null
        for (i in 0 until clip.itemCount) {
            val it = clip.getItemAt(i)
            val uri = it.uri
            if (uri != null) { val e = addUri(c, uri, clip.description.getMimeType(0)); if (e == null) added++ else err = e }
            else {
                val t = try { it.coerceToText(ctx)?.toString() } catch (_: Exception) { null }
                if (!t.isNullOrBlank() && addText(c, t) == null) added++
            }
        }
        return if (added > 0) null else (err ?: "Nothing usable on the clipboard.")
    }

    fun addClip(c: Context, clip: ClipData?): String? {
        if (clip == null) return "Nothing came across."
        var added = 0; var err: String? = null
        for (i in 0 until clip.itemCount) {
            val it = clip.getItemAt(i)
            val uri = it.uri
            if (uri != null) { val e = addUri(c, uri, clip.description.getMimeType(0)); if (e == null) added++ else err = e }
            else { val t = it.text?.toString(); if (!t.isNullOrBlank() && addText(c, t) == null) added++ }
        }
        return if (added > 0) null else (err ?: "Nothing usable came across.")
    }

    fun remove(c: Context, id: String) {
        val a = load(c); val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == id) o.optString("path").takeIf { it.isNotBlank() }?.let { File(it).delete() } else keep.put(o)
        }
        save(c, keep)
    }

    fun clear(c: Context) {
        val a = load(c)
        for (i in 0 until a.length()) a.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        save(c, JSONArray())
    }

    // ─────────────── quick files: pinned, and the ones you keep opening ───────────────

    private const val AUTO_AFTER = 3   // opened this many times -> shows up by itself

    private fun qf(c: Context): JSONObject = try { JSONObject(sp(c).getString("qf", "{}")) } catch (_: Exception) { JSONObject() }
    private fun saveQf(c: Context, o: JSONObject) {
        // keep the file small: forget entries whose file is gone, and the least used beyond 200
        for (k in o.keys().asSequence().toList()) if (!File(k).exists()) o.remove(k)
        if (o.length() > 200) {
            val ks = o.keys().asSequence().toList().sortedBy { o.getJSONObject(it).optInt("o") }
            for (k in ks.take(o.length() - 200)) o.remove(k)
        }
        sp(c).edit().putString("qf", o.toString()).apply()
    }

    /** A file was opened through Pragon (sidebar, My Files). */
    fun touch(c: Context, path: String) {
        if (!File(path).isFile) return
        val o = qf(c)
        val e = o.optJSONObject(path) ?: JSONObject()
        e.put("o", e.optInt("o") + 1).put("t", System.currentTimeMillis())
        o.put(path, e); saveQf(c, o)
    }

    fun isPinned(c: Context, path: String) = qf(c).optJSONObject(path)?.optBoolean("p") == true

    fun pin(c: Context, path: String, on: Boolean) {
        val o = qf(c)
        val e = o.optJSONObject(path) ?: JSONObject()
        if (on) e.put("p", true).put("t", System.currentTimeMillis())
        else { e.put("p", false); if (e.optInt("o") >= AUTO_AFTER) e.put("o", 0) }   // unpinning also hides an auto entry
        o.put(path, e); saveQf(c, o)
    }

    fun quick(c: Context): List<FEntry> {
        val o = qf(c); val out = ArrayList<FEntry>()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            val f = File(k)
            if (!f.isFile) continue
            if (!e.optBoolean("p") && e.optInt("o") < AUTO_AFTER) continue
            out.add(FEntry("q:$k", f.name, k, null, if (extOf(f.name) in IMG) "image" else "file", f.length(), e.optLong("t"), extOf(f.name), e.optInt("o"), e.optBoolean("p")))
        }
        return out
    }

    // ─────────────── doc box: what you downloaded lately ───────────────

    private fun extWhere(exts: List<String>) = "(" + exts.joinToString(" OR ") { "_display_name LIKE ?" } + ")"

    /** filter: all | pdf | doc | xls | ppt | txt | zip | apk. "all" = every document / archive / APK anywhere, plus anything at all in Downloads. Newest 500 by the time it was added. */
    fun docs(c: Context, filter: String): List<FEntry> {
        val exts = if (filter == "all") EXT.values.flatten() else (EXT[filter] ?: emptyList())
        if (exts.isEmpty()) return emptyList()
        val args = ArrayList<String>()
        var where = extWhere(exts); exts.forEach { args.add("%.$it") }
        if (filter == "all") { where = "($where OR _data LIKE ?)"; args.add("%/Download/%") }
        where += " AND _size>0 AND _data NOT LIKE ? AND _data NOT LIKE ? AND _data NOT LIKE ? AND _data NOT LIKE ?"
        args.addAll(listOf("%/Android/data/%", "%/Android/obb/%", "%/.%", "%.crdownload"))
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf("date_added"))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 500)
        }
        val out = ArrayList<FEntry>()
        try {
            c.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf("_data", "_display_name", "_size", "date_added"), b, null)?.use { q ->
                while (q.moveToNext()) {
                    val p = q.getString(0) ?: continue
                    val n = q.getString(1) ?: File(p).name
                    out.add(FEntry("d:$p", n, p, null, if (extOf(n) in IMG) "image" else "file", q.getLong(2), q.getLong(3) * 1000, extOf(n)))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    // ─────────────── opening, sharing, dragging out ───────────────

    fun mime(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)

    /** What a drag carries: the text itself, or a content:// address of the file (the other app is given read access for the drop). */
    fun clipFor(c: Context, e: FEntry): ClipData? {
        if (e.kind == "text" || e.kind == "link") return ClipData.newPlainText(e.name, e.text ?: e.name)
        val f = File(e.path ?: return null)
        if (!f.isFile) return null
        return try { ClipData.newUri(c.contentResolver, e.name, uriFor(c, f)) } catch (_: Exception) { null }
    }

    fun open(c: Context, e: FEntry): Boolean = try {
        when (e.kind) {
            "link" -> c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "text" -> copyText(c, e.text ?: e.name)
            else -> {
                val f = File(e.path ?: return false)
                c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(c, f), mime(f))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                if (!f.absolutePath.startsWith(dir(c).absolutePath)) touch(c, f.absolutePath)   // files in the dock are copies: not counted
            }
        }
        true
    } catch (_: Exception) { false }

    fun share(c: Context, e: FEntry): Boolean = try {
        val i = Intent(Intent.ACTION_SEND)
        if (e.kind == "text" || e.kind == "link") i.setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.text ?: e.name)
        else { val f = File(e.path ?: return false); i.setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uriFor(c, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        c.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    fun copyText(c: Context, t: String) {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pragon", t))
    }

    // ─────────────── look ───────────────

    fun badge(e: FEntry): String = when (e.kind) { "link" -> "URL"; "text" -> "TXT"; else -> e.ext.take(4).uppercase().ifEmpty { "FILE" } }

    fun badgeColor(e: FEntry): Int = when {
        e.kind == "link" -> 0xFF0E7490.toInt()
        e.kind == "text" -> 0xFF475569.toInt()
        e.kind == "image" || e.ext in IMG -> 0xFF2F7BF5.toInt()
        e.ext == "pdf" -> 0xFFD64545.toInt()
        e.ext in (EXT["doc"] ?: emptyList()) -> 0xFF1E7BD8.toInt()
        e.ext in (EXT["xls"] ?: emptyList()) -> 0xFF1F9D55.toInt()
        e.ext in (EXT["ppt"] ?: emptyList()) -> 0xFFE0762B.toInt()
        e.ext in (EXT["zip"] ?: emptyList()) -> 0xFF8B6B4A.toInt()
        e.ext == "apk" -> 0xFF2FB45A.toInt()
        else -> 0xFF4A5568.toInt()
    }

    fun size(b: Long): String = when {
        b < 1000 -> "$b B"
        b < 1_000_000 -> "${b / 1000} KB"
        b < 1_000_000_000 -> String.format("%.1f MB", b / 1e6)
        else -> String.format("%.1f GB", b / 1e9)
    }
}
