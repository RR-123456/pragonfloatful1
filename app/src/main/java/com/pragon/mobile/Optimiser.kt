package com.pragon.mobile

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * v15: the engine behind the Phone optimiser (assets/optimiser.html).
 *   JUNK SCAN   walks the phone's storage once and groups what it finds: temp and log files, thumbnail caches, empty files,
 *               empty folders, half-finished downloads, old installers (APKs), duplicate files and very large files.
 *               True junk is deleted. Anything you might still want (duplicates, installers, large files) goes to the same
 *               30-day "Recently deleted" bin as My Files, so it can be restored.
 *   APP CACHE   Android does not let one app clear another app's cache, so Pragon opens each app's own App info > Storage
 *               screen and presses "Clear cache" for you through the accessibility service. Pragon's own cache is cleared directly.
 *   BOOST       asks Android to end the background processes of apps you did not protect, and reports the memory before and after.
 * Nothing here uses the network.
 */
object Optimiser {

    // ───────────────────────── scan result model ─────────────────────────

    class Item(val path: String, val size: Long, val dir: Boolean = false, val note: String = "")

    class Cat(val id: String, val title: String, val desc: String, val safe: Boolean, val toBin: Boolean) {
        val items = ArrayList<Item>()
        var bytes = 0L
        var count = 0
    }

    class ScanState {
        @Volatile var running = false
        @Volatile var phase = ""
        @Volatile var files = 0
        @Volatile var found = 0L
        @Volatile var cancel = false
        @Volatile var done = false
        @Volatile var error = ""
        @Volatile var cats: List<Cat> = emptyList()
        @Volatile var at = 0L
    }

    val scan = ScanState()

    private const val BIG = 100L * 1024 * 1024
    private const val DUP_MIN = 100L * 1024
    private const val HASH_MAX = 600L * 1024 * 1024

    private val TEMP_EXT = setOf("tmp", "temp", "log", "dmp", "chk", "old", "crash")
    private val PART_EXT = setOf("crdownload", "part", "partial", "download", "opdownload")
    private val KEEP_DIRS = setOf("android", "download", "downloads", "dcim", "pictures", "movies", "music", "documents", "alarms", "notifications",
        "ringtones", "podcasts", "audiobooks", "recordings", "screenshots", "camera")

    private fun sp(c: Context) = c.getSharedPreferences("pragon_optimiser", Context.MODE_PRIVATE)

    fun hasAll(): Boolean = Environment.isExternalStorageManager()

    // ───────────────────────── junk scan ─────────────────────────

    fun startScan(ctx: Context) {
        if (scan.running) return
        scan.running = true; scan.done = false; scan.cancel = false; scan.error = ""; scan.files = 0; scan.found = 0; scan.phase = "Starting"
        scan.cats = emptyList()
        val app = ctx.applicationContext
        Thread {
            try { scan.cats = runScan(app) ; scan.at = System.currentTimeMillis() }
            catch (e: Throwable) { scan.error = e.message ?: "scan failed" }
            scan.running = false; scan.done = true
        }.start()
    }

    private fun runScan(ctx: Context): List<Cat> {
        val root = Environment.getExternalStorageDirectory()
        val trash = File(root, ".PragonTrash")
        val tmp = Cat("temp", "Temp and log files", "Leftover .tmp, .log and crash files that apps forgot to remove.", true, false)
        val thumbs = Cat("thumbs", "Thumbnail caches", "Picture previews the gallery can rebuild by itself.", true, false)
        val trashed = Cat("trashed", "Trashed media", "Photos and videos Android already put in its own bin (named .trashed-…).", true, false)
        val emptyF = Cat("emptyfiles", "Empty files", "Files with nothing inside (0 bytes). .nomedia markers are left alone.", true, false)
        val emptyD = Cat("emptydirs", "Empty folders", "Folders with nothing in them or only more empty folders.", true, false)
        val part = Cat("partial", "Unfinished downloads", "Downloads that stopped half way (.crdownload, .part). Check them if you meant to resume.", false, true)
        val apks = Cat("apks", "Old installers (APK)", "Install files you already used. Sent to Recently deleted, so you can bring them back.", false, true)
        val dups = Cat("dups", "Duplicate files", "Exact copies. The oldest copy of each is kept; the extra copies go to Recently deleted.", false, true)
        val big = Cat("big", "Large files (100 MB+)", "Your biggest files. Nothing is picked for you: choose what you do not need.", false, true)
        val bin = Cat("bin", "Recently deleted bin", "Files in the My Files bin. Emptying it deletes them for good.", false, false)
        val own = Cat("own", "Pragon's own cache", "Temporary files made by Pragon itself.", true, false)

        // Pragon's own cache and the bin
        scan.phase = "Checking Pragon's cache"
        for (d in listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)) {
            val s = sizeOf(d)
            if (s > 0) { own.items.add(Item(d.absolutePath, s, true, "folder")); own.bytes += s; own.count++ }
        }
        if (trash.isDirectory) {
            val s = sizeOf(trash)
            if (s > 0) { bin.items.add(Item(trash.absolutePath, s, true, "bin")); bin.bytes += s; bin.count = (trash.list()?.size ?: 0) }
        }

        // the walk
        val bySize = HashMap<Long, MutableList<File>>()
        var fileCount = 0
        fun add(c: Cat, f: File, size: Long, dir: Boolean = false, note: String = "") { c.items.add(Item(f.absolutePath, size, dir, note)); c.bytes += size; c.count++; scan.found += size }

        // thumbnails folders live in hidden directories that the walk skips: look at them directly
        scan.phase = "Looking at thumbnails"
        for (p in listOf(".thumbnails", "DCIM/.thumbnails", "Pictures/.thumbnails", "Android/data/com.android.gallery3d/cache")) {
            val d = File(root, p)
            if (d.isDirectory) { val s = sizeOf(d); if (s > 0) add(thumbs, d, s, true, "folder") }
        }

        scan.phase = "Scanning files"
        fun walk(dir: File, depth: Int): Boolean {          // true = this folder holds no files at all (only empty folders)
            if (scan.cancel) return false
            val kids = try { dir.listFiles() } catch (_: Exception) { null } ?: return false
            if (kids.isEmpty()) return true
            var allEmpty = true
            val emptyKids = ArrayList<File>()
            for (k in kids) {
                if (scan.cancel) return false
                val name = k.name
                if (k.isDirectory) {
                    if (name.startsWith(".")) { allEmpty = false; continue }
                    if (depth == 0 && name.equals("Android", true)) { allEmpty = false; continue }
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) { allEmpty = false; continue }
                    val e = walk(k, depth + 1)
                    if (e) emptyKids.add(k) else allEmpty = false
                } else {
                    allEmpty = false
                    fileCount++
                    if (fileCount % 200 == 0) scan.files = fileCount
                    val len = k.length()
                    val lower = name.lowercase()
                    val ext = lower.substringAfterLast('.', "")
                    when {
                        lower.startsWith(".trashed-") -> add(trashed, k, len)
                        lower == ".nomedia" -> {}
                        len == 0L -> add(emptyF, k, 0)
                        ext in TEMP_EXT || lower.endsWith("~") || lower.startsWith("thumbdata") -> add(tmp, k, len)
                        ext in PART_EXT -> add(part, k, len)
                        ext == "apk" -> add(apks, k, len)
                        else -> {
                            if (len >= BIG) add(big, k, len, false, "")
                            if (len >= DUP_MIN && !name.startsWith(".")) bySize.getOrPut(len) { ArrayList() }.add(k)
                        }
                    }
                }
            }
            // report the top-most empty folders only (a folder whose parent is not itself empty)
            if (!allEmpty || depth == 0) {
                for (e in emptyKids) {
                    if (depth == 0 && e.name.lowercase() in KEEP_DIRS) continue
                    if (e.name.lowercase() in KEEP_DIRS && depth <= 1) continue
                    add(emptyD, e, 0, true, "folder")
                }
            }
            return allEmpty
        }
        walk(root, 0)
        scan.files = fileCount

        // duplicates: same size, then same fingerprint, then same full hash
        scan.phase = "Comparing files"
        val dupItems = ArrayList<Item>()
        for ((size, list) in bySize) {
            if (scan.cancel) break
            if (list.size < 2 || size > HASH_MAX) continue
            val groups = HashMap<String, MutableList<File>>()
            for (f in list) { val h = hashOf(f, partial = true) ?: continue; groups.getOrPut(h) { ArrayList() }.add(f) }
            for (g in groups.values) {
                if (g.size < 2) continue
                val full = HashMap<String, MutableList<File>>()
                for (f in g) { val h = hashOf(f, partial = false) ?: continue; full.getOrPut(h) { ArrayList() }.add(f) }
                for (same in full.values) {
                    if (same.size < 2) continue
                    val sorted = same.sortedBy { it.lastModified() }
                    val keep = sorted.first()
                    for (extra in sorted.drop(1)) dupItems.add(Item(extra.absolutePath, size, false, keep.absolutePath))
                }
            }
        }
        for (d in dupItems) { dups.items.add(d); dups.bytes += d.size; dups.count++; scan.found += d.size }
        // a big file that is also a duplicate is listed once (under Duplicates)
        val dupPaths = dupItems.mapTo(HashSet()) { it.path }
        big.items.removeAll { it.path in dupPaths }
        big.bytes = big.items.sumOf { it.size }; big.count = big.items.size

        val all = listOf(own, tmp, thumbs, trashed, emptyF, emptyD, part, apks, dups, big, bin)
        for (c in all) c.items.sortByDescending { it.size }
        scan.phase = "Done"
        return all.filter { it.count > 0 || it.bytes > 0 }
    }

    private fun sizeOf(d: File): Long {
        var n = 0L
        try { for (f in d.walkTopDown()) if (f.isFile) n += f.length() } catch (_: Exception) { }
        return n
    }

    private fun hashOf(f: File, partial: Boolean): String? = try {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            if (partial) {
                var n = ins.read(buf); if (n > 0) md.update(buf, 0, n)
                val len = f.length()
                if (len > buf.size * 2L) {
                    ins.skip(len / 2 - buf.size)
                    n = ins.read(buf); if (n > 0) md.update(buf, 0, n)
                }
            } else {
                while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    fun stateJson(): String {
        val o = JSONObject().put("running", scan.running).put("done", scan.done).put("phase", scan.phase)
            .put("files", scan.files).put("found", scan.found).put("error", scan.error).put("at", scan.at)
        return o.toString()
    }

    /** Summary + the first [limit] items of every category. */
    fun resultJson(limit: Int = 300): String {
        val a = JSONArray()
        for (c in scan.cats) {
            val items = JSONArray()
            for (i in c.items.take(limit)) items.put(JSONObject().put("p", i.path).put("s", i.size).put("d", i.dir).put("o", i.note))
            a.put(JSONObject().put("id", c.id).put("t", c.title).put("desc", c.desc).put("safe", c.safe).put("bin", c.toBin)
                .put("bytes", c.bytes).put("count", c.count).put("items", items).put("more", maxOf(0, c.items.size - limit)))
        }
        return JSONObject().put("cats", a).toString()
    }

    // ───────────────────────── cleaning ─────────────────────────

    /** request: [{"id":"temp","exclude":["/sdcard/a.tmp"]}, ...]. Returns {"bytes":..,"count":..,"failed":..}. */
    fun clean(ctx: Context, request: String): String {
        val req = try { JSONArray(request) } catch (_: Exception) { return JSONObject().put("bytes", 0).put("count", 0).put("failed", 0).toString() }
        var bytes = 0L; var count = 0; var failed = 0
        val touched = ArrayList<String>()
        for (i in 0 until req.length()) {
            val r = req.getJSONObject(i)
            val cat = scan.cats.firstOrNull { it.id == r.getString("id") } ?: continue
            val ex = HashSet<String>()
            r.optJSONArray("exclude")?.let { for (k in 0 until it.length()) ex.add(it.getString(k)) }
            val only = r.optJSONArray("only")?.let { a -> HashSet<String>().also { for (k in 0 until a.length()) it.add(a.getString(k)) } }
            val done = ArrayList<Item>()
            for (it in cat.items) {
                if (it.path in ex) continue
                if (only != null && it.path !in only) continue
                val f = File(it.path)
                if (!f.exists()) { done.add(it); continue }
                if (!safePath(ctx, f)) { failed++; continue }
                val ok = when {
                    cat.id == "bin" -> { f.listFiles()?.forEach { x -> x.deleteRecursively() }; ctx.getSharedPreferences("pragon_files", Context.MODE_PRIVATE).edit().putString("trash", "{}").apply(); true }
                    cat.id == "own" -> { f.listFiles()?.forEach { x -> x.deleteRecursively() }; true }
                    cat.toBin -> Bin.toTrash(ctx, f)
                    f.isDirectory -> f.deleteRecursively()
                    else -> f.delete()
                }
                if (ok) { bytes += it.size; count++; done.add(it); touched.add(it.path) } else failed++
            }
            cat.items.removeAll(done.toSet())
            cat.bytes = cat.items.sumOf { x -> x.size }; cat.count = cat.items.size
        }
        scan.cats = scan.cats.filter { it.items.isNotEmpty() }
        if (touched.isNotEmpty()) try { MediaScannerConnection.scanFile(ctx, touched.take(500).toTypedArray(), null, null) } catch (_: Exception) { }
        sp(ctx).edit().putLong("freed_total", sp(ctx).getLong("freed_total", 0) + bytes).putLong("last_clean", System.currentTimeMillis()).putLong("last_freed", bytes).apply()
        return JSONObject().put("bytes", bytes).put("count", count).put("failed", failed).toString()
    }

    /** Only things on shared storage, or Pragon's own cache, may ever be deleted from here. */
    private fun safePath(ctx: Context, f: File): Boolean = try {
        val c = f.canonicalPath
        val root = Environment.getExternalStorageDirectory().canonicalPath
        (c.startsWith("$root/") && !c.startsWith("$root/Android/") && c != root) ||
            c.startsWith(ctx.cacheDir.canonicalPath) || (ctx.externalCacheDir?.let { c.startsWith(it.canonicalPath) } == true)
    } catch (_: Exception) { false }

    /** The same 30-day bin My Files uses (/sdcard/.PragonTrash plus the "trash" list in pragon_files). */
    object Bin {
        fun toTrash(ctx: Context, f: File): Boolean {
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, ".PragonTrash")
            dir.mkdirs()
            val id = "${System.currentTimeMillis()}_${f.name}"
            val dest = File(dir, id)
            val ok = f.renameTo(dest) || (try { f.copyRecursively(dest, true) && f.deleteRecursively() } catch (_: Exception) { false })
            if (ok) {
                val p = ctx.getSharedPreferences("pragon_files", Context.MODE_PRIVATE)
                val m = try { JSONObject(p.getString("trash", "{}")) } catch (_: Exception) { JSONObject() }
                m.put(id, JSONObject().put("orig", f.absolutePath).put("ts", System.currentTimeMillis()))
                p.edit().putString("trash", m.toString()).apply()
            }
            return ok
        }
    }

    // ───────────────────────── apps and cache ─────────────────────────

    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    class AppRow(val pkg: String, val label: String, val cache: Long, val data: Long, val app: Long, val system: Boolean)

    /** Every app with a launcher icon. Sizes are filled in only when Usage access is on (Android's rule for reading other apps' sizes). */
    fun apps(ctx: Context): List<AppRow> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppRow>()
        val usage = hasUsageAccess(ctx)
        val ssm = if (usage) ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager else null
        val user = Process.myUserHandle()
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        for (ri in launcher) {
            val pkg = ri.activityInfo.packageName
            if (!seen.add(pkg)) continue
            val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { continue }
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { pkg }
            var cache = -1L; var data = 0L; var app = 0L
            if (ssm != null) try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                cache = st.cacheBytes; data = st.dataBytes; app = st.appBytes
            } catch (_: Exception) { }
            out.add(AppRow(pkg, label, cache, data, app, (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0))
        }
        return out
    }

    fun appsJson(ctx: Context): String {
        val a = JSONArray()
        for (r in apps(ctx).sortedWith(compareByDescending<AppRow> { it.cache }.thenBy { it.label.lowercase() }))
            a.put(JSONObject().put("pkg", r.pkg).put("n", r.label).put("cache", r.cache).put("data", r.data).put("app", r.app).put("sys", r.system))
        return JSONObject().put("usage", hasUsageAccess(ctx)).put("a11y", PragonAccessibilityService.instance != null).put("apps", a).toString()
    }

    class CacheState {
        @Volatile var running = false
        @Volatile var cancel = false
        @Volatile var total = 0
        @Volatile var done = 0
        @Volatile var cleared = 0
        @Volatile var skipped = 0
        @Volatile var failed = 0
        @Volatile var current = ""
        @Volatile var message = ""
        @Volatile var finished = false
    }
    val cache = CacheState()

    fun cacheJson(): String = JSONObject().put("running", cache.running).put("total", cache.total).put("done", cache.done)
        .put("cleared", cache.cleared).put("skipped", cache.skipped).put("failed", cache.failed).put("current", cache.current)
        .put("message", cache.message).put("finished", cache.finished).toString()

    /** Opens each chosen app's App info > Storage and presses Clear cache. Runs on its own thread; the page polls cacheJson(). */
    fun clearCaches(ctx: Context, pkgsJson: String): String {
        if (cache.running) return "Already running."
        val svc = PragonAccessibilityService.instance ?: return "Turn on Pragon's accessibility first (Settings > Setup)."
        val pkgs = try { JSONArray(pkgsJson).let { a -> List(a.length()) { a.getString(it) } } } catch (_: Exception) { emptyList() }
        if (pkgs.isEmpty()) return "Choose at least one app."
        val app = ctx.applicationContext
        cache.running = true; cache.cancel = false; cache.finished = false; cache.total = pkgs.size; cache.done = 0
        cache.cleared = 0; cache.skipped = 0; cache.failed = 0; cache.current = ""; cache.message = ""
        Thread {
          try {
            val pm = app.packageManager
            for (pkg in pkgs) {
                if (cache.cancel) break
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                cache.current = label
                if (pkg == app.packageName) {
                    app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    app.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                    cache.cleared++
                } else {
                    try {
                        app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        try { Thread.sleep(700) } catch (_: InterruptedException) { }
                        when (svc.clearCacheCurrentAppInfo()) {
                            PragonAccessibilityService.CacheResult.DONE -> cache.cleared++
                            PragonAccessibilityService.CacheResult.EMPTY -> cache.skipped++
                            PragonAccessibilityService.CacheResult.NO_STORAGE, PragonAccessibilityService.CacheResult.NO_BUTTON -> cache.failed++
                        }
                    } catch (_: Exception) { cache.failed++ }
                }
                cache.done++
            }
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            try { Thread.sleep(500) } catch (_: InterruptedException) { }
            try { app.startActivity(Intent(app, OptimiserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("tab", "cache")) } catch (_: Exception) { }
            cache.current = ""
            cache.message = if (cache.failed > 0 && cache.cleared == 0) "This phone's Settings screen did not match what Pragon looks for. Open the app's Storage screen and press Clear cache by hand."
                else "Done."
            cache.running = false; cache.finished = true
            sp(app).edit().putLong("last_cache", System.currentTimeMillis()).apply()
          } catch (t: Throwable) {
            // Never leave the page stuck on "Already running", and never crash the app from this thread.
            cache.message = "Stopped early: " + (t.message ?: t.javaClass.simpleName)
          } finally {
            cache.current = ""; cache.running = false; cache.finished = true
          }
        }.start()
        return ""
    }

    // ───────────────────────── boost (memory) ─────────────────────────

    fun keepSet(ctx: Context): MutableSet<String> {
        val a = try { JSONArray(sp(ctx).getString("keep", "[]")) } catch (_: Exception) { JSONArray() }
        return HashSet<String>().also { for (i in 0 until a.length()) it.add(a.getString(i)) }
    }
    fun setKeep(ctx: Context, pkg: String, on: Boolean) {
        val s = keepSet(ctx); if (on) s.add(pkg) else s.remove(pkg)
        sp(ctx).edit().putString("keep", JSONArray(s.toList()).toString()).apply()
    }

    private fun mem(ctx: Context): ActivityManager.MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi
    }

    fun memJson(ctx: Context): String { val m = mem(ctx); return JSONObject().put("total", m.totalMem).put("avail", m.availMem).toString() }

    fun storageJson(): String {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        return JSONObject().put("total", st.totalBytes).put("free", st.availableBytes).toString()
    }

    fun homeJson(ctx: Context): String {
        val p = sp(ctx)
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        val m = mem(ctx)
        return JSONObject().put("perm", hasAll()).put("a11y", PragonAccessibilityService.instance != null).put("usage", hasUsageAccess(ctx))
            .put("sTotal", st.totalBytes).put("sFree", st.availableBytes).put("mTotal", m.totalMem).put("mFree", m.availMem)
            .put("freedTotal", p.getLong("freed_total", 0)).put("lastClean", p.getLong("last_clean", 0)).put("lastFreed", p.getLong("last_freed", 0))
            .put("lastBoost", p.getLong("last_boost", 0)).put("lastCache", p.getLong("last_cache", 0)).toString()
    }

    /** Ends background processes of launcher apps except the ones kept. Memory freed is the difference measured a moment later. */
    fun boost(ctx: Context): String {
        val pm = ctx.packageManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val keep = HashSet<String>(keepSet(ctx))
        keep.add(ctx.packageName); keep.add("com.android.systemui"); keep.add("android"); keep.add("com.android.phone")
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName?.let { keep.add(it) }
        pm.resolveActivity(Intent(Intent.ACTION_DIAL), 0)?.activityInfo?.packageName?.let { keep.add(it) }
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.enabledInputMethodList.forEach { keep.add(it.packageName) }
        } catch (_: Exception) { }
        val before = mem(ctx).availMem
        // apps that are really running right now are the ones worth asking to stop
        val running = HashSet<String>()
        try { am.runningAppProcesses?.forEach { p -> p.pkgList?.forEach { running.add(it) } } } catch (_: Exception) { }
        val targets = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { it !in keep }
        var asked = 0
        for (p in targets) try { am.killBackgroundProcesses(p); asked++ } catch (_: Exception) { }
        try { Thread.sleep(900) } catch (_: InterruptedException) { }
        val after = mem(ctx).availMem
        sp(ctx).edit().putLong("last_boost", System.currentTimeMillis()).apply()
        val m = mem(ctx)
        return JSONObject().put("before", before).put("after", after).put("total", m.totalMem).put("asked", asked)
            .put("running", running.count { it !in keep }).toString()
    }
}
