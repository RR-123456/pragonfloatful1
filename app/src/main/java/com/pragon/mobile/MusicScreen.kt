package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/** v18: the library behind the Music screen. Reads the phone's songs; keeps favourites, hidden songs and playlists in Pragon. */
object MusicLibrary {
    class Song(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long,
               val ms: Long, val size: Long, val added: Long, val name: String, val folder: String, val year: Int)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_library", Context.MODE_PRIVATE)

    fun all(c: Context): List<Song> {
        if (!Music.hasAccess(c)) return emptyList()
        val out = ArrayList<Song>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATA)
        try {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val path = cur.getString(10) ?: ""
                    out.add(Song(cur.getLong(0), cur.getString(1) ?: "Unknown", cur.getString(2) ?: "<unknown>",
                        cur.getString(3) ?: "", cur.getLong(4), cur.getLong(5), cur.getLong(6), cur.getLong(7),
                        cur.getString(8) ?: "", path.substringBeforeLast('/', ""), cur.getInt(9)))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun idSet(c: Context, key: String): MutableSet<Long> =
        (sp(c).getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toMutableSet()
    private fun saveIds(c: Context, key: String, s: Set<Long>) = sp(c).edit().putStringSet(key, s.map { it.toString() }.toSet()).apply()

    fun favs(c: Context) = idSet(c, "fav")
    fun setFav(c: Context, id: Long, on: Boolean) { val s = favs(c); if (on) s.add(id) else s.remove(id); saveIds(c, "fav", s) }
    fun hidden(c: Context) = idSet(c, "hidden")
    fun hide(c: Context, id: Long) { val s = hidden(c); s.add(id); saveIds(c, "hidden", s) }

    fun playlists(c: Context): JSONObject = try { JSONObject(sp(c).getString("playlists", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun savePlaylists(c: Context, o: JSONObject) = sp(c).edit().putString("playlists", o.toString()).apply()
    fun newPlaylist(c: Context, name: String) {
        val n = name.trim().take(40); if (n.isEmpty()) return
        val o = playlists(c); if (!o.has(n)) o.put(n, JSONArray()); savePlaylists(c, o)
    }
    fun addToPlaylist(c: Context, name: String, id: Long) {
        val o = playlists(c); val arr = o.optJSONArray(name) ?: JSONArray()
        for (i in 0 until arr.length()) if (arr.optLong(i) == id) return
        arr.put(id); o.put(name, arr); savePlaylists(c, o)
    }
    fun removeFromPlaylist(c: Context, name: String, id: Long) {
        val o = playlists(c); val arr = o.optJSONArray(name) ?: return
        val keep = JSONArray(); for (i in 0 until arr.length()) if (arr.optLong(i) != id) keep.put(arr.optLong(i))
        o.put(name, keep); savePlaylists(c, o)
    }

    fun minSeconds(c: Context) = sp(c).getInt("min_sec", 10)
    fun dedupe(c: Context) = sp(c).getBoolean("dedupe", false)
    fun setPrefs(c: Context, minSec: Int, dedupe: Boolean) = sp(c).edit().putInt("min_sec", minSec).putBoolean("dedupe", dedupe).apply()

    /** Songs after the user's filters, sorted. req: {sort, asc, q, scope, album, artist, folder, playlist}. */
    fun songs(c: Context, req: JSONObject): List<Song> {
        val hid = hidden(c); val fav = favs(c)
        val minMs = minSeconds(c) * 1000L
        var l = all(c).filter { it.ms >= minMs && it.id !in hid }
        when (req.optString("scope")) {
            "fav" -> l = l.filter { it.id in fav }
            "album" -> l = l.filter { it.album == req.optString("album") }
            "artist" -> l = l.filter { it.artist == req.optString("artist") }
            "folder" -> l = l.filter { it.folder == req.optString("folder") }
            "playlist" -> {
                val arr = playlists(c).optJSONArray(req.optString("playlist")) ?: JSONArray()
                val ids = (0 until arr.length()).map { arr.optLong(it) }.toSet()
                l = l.filter { it.id in ids }
            }
        }
        val q = req.optString("q").trim().lowercase()
        if (q.isNotEmpty()) l = l.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
        if (dedupe(c)) l = l.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}|${it.ms / 1000}" }
        val sorted = when (req.optString("sort", "title")) {
            "file" -> l.sortedBy { it.name.lowercase() }
            "artist" -> l.sortedBy { it.artist.lowercase() }
            "duration" -> l.sortedBy { it.ms }
            "year" -> l.sortedBy { it.year }
            "added", "modified" -> l.sortedBy { it.added }
            "size" -> l.sortedBy { it.size }
            else -> l.sortedBy { it.title.lowercase() }
        }
        return if (req.optBoolean("asc", true)) sorted else sorted.reversed()
    }

    fun songsJson(c: Context, req: JSONObject): String {
        val fav = favs(c); val arr = JSONArray()
        songs(c, req).forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                .put("ms", s.ms).put("folder", s.folder).put("name", s.name).put("fav", s.id in fav))
        }
        return arr.toString()
    }

    /** Albums, artists or folders with a count, for the tabs. */
    fun groupsJson(c: Context, kind: String): String {
        val hid = hidden(c); val minMs = minSeconds(c) * 1000L
        val visible = all(c).filter { it.ms >= minMs && it.id !in hid }
        val key: (Song) -> String = when (kind) {
            "artists" -> { s -> s.artist }
            "folders" -> { s -> s.folder }
            else -> { s -> s.album }
        }
        val arr = JSONArray()
        visible.groupBy(key).entries.sortedBy { it.key.lowercase() }.forEach { (k, v) ->
            arr.put(JSONObject().put("name", k.ifEmpty { "Unknown" }).put("count", v.size))
        }
        return arr.toString()
    }

    fun playlistsJson(c: Context): String {
        val o = playlists(c); val arr = JSONArray()
        o.keys().asSequence().sorted().forEach { k -> arr.put(JSONObject().put("name", k).put("count", o.optJSONArray(k)?.length() ?: 0)) }
        return arr.toString()
    }
}

/** v18: Music screen (assets/music.html). Opened from Tools > Music. */
class MusicActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "PragonMusic")
            loadUrl("file:///android_asset/music.html")
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun tracksFor(ids: JSONArray): List<Music.Track> {
        val byId = MusicLibrary.all(this).associateBy { it.id }
        return (0 until ids.length()).mapNotNull { byId[ids.optLong(it)] }
            .map { Music.Track(it.id, it.title, it.artist, it.album, it.ms) }
    }

    inner class Bridge {
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
        @JavascriptInterface fun access(): Boolean = Music.hasAccess(this@MusicActivity)
        @JavascriptInterface fun askAccess() = runOnUiThread { Music.requestAccess(this@MusicActivity) }
        @JavascriptInterface fun songs(req: String): String =
            try { MusicLibrary.songsJson(this@MusicActivity, JSONObject(req)) } catch (e: Exception) { "[]" }
        @JavascriptInterface fun groups(kind: String): String =
            try { MusicLibrary.groupsJson(this@MusicActivity, kind) } catch (e: Exception) { "[]" }
        @JavascriptInterface fun playlists(): String =
            try { MusicLibrary.playlistsJson(this@MusicActivity) } catch (e: Exception) { "[]" }
        @JavascriptInterface fun newPlaylist(name: String) = MusicLibrary.newPlaylist(this@MusicActivity, name)
        @JavascriptInterface fun addToPlaylist(name: String, id: Double) = MusicLibrary.addToPlaylist(this@MusicActivity, name, id.toLong())
        @JavascriptInterface fun removeFromPlaylist(name: String, id: Double) = MusicLibrary.removeFromPlaylist(this@MusicActivity, name, id.toLong())
        @JavascriptInterface fun setFav(id: Double, on: Boolean) = MusicLibrary.setFav(this@MusicActivity, id.toLong(), on)
        @JavascriptInterface fun hide(id: Double) { MusicLibrary.hide(this@MusicActivity, id.toLong()); toast("Hidden from the list.") }
        @JavascriptInterface fun setPrefs(minSec: Int, dedupe: Boolean) = MusicLibrary.setPrefs(this@MusicActivity, minSec, dedupe)
        @JavascriptInterface fun prefs(): String = JSONObject().put("minSec", MusicLibrary.minSeconds(this@MusicActivity))
            .put("dedupe", MusicLibrary.dedupe(this@MusicActivity)).toString()

        /** Plays the ids (JSON array) starting at index start. */
        @JavascriptInterface fun play(idsJson: String, start: Int) {
            Thread {
                try {
                    val tracks = tracksFor(JSONArray(idsJson))
                    if (tracks.isEmpty()) { toast("No songs to play."); return@Thread }
                    val r = Music.playTracks(this@MusicActivity, tracks, start.coerceIn(0, tracks.size - 1))
                    if (!r.ok) toast(r.msg)
                    else runOnUiThread { try { startForegroundService(Intent(this@MusicActivity, MusicService::class.java).setAction("noop")) } catch (_: Exception) { } }
                } catch (e: Exception) { toast("Could not play that song.") }
            }.start()
        }
        @JavascriptInterface fun pauseToggle() { Thread { Music.pauseToggle(this@MusicActivity) }.start() }
        @JavascriptInterface fun skip(d: Int) { Thread { Music.skip(this@MusicActivity, d) }.start() }
        @JavascriptInterface fun seek(ms: Int) { Thread { Music.seekTo(ms) }.start() }
        @JavascriptInterface fun speed(f: Double, pitch: Double) { Thread { Music.setSpeed(f.toFloat(), pitch.toFloat()) }.start() }

        /** Now playing: {title, artist, playing, pos, dur}. */
        @JavascriptInterface fun nowPlaying(): String {
            val t = Music.current()
            return JSONObject().put("has", t != null).put("title", t?.title ?: "").put("artist", t?.artist ?: "")
                .put("playing", Music.isPlaying()).put("pos", Music.position()).put("dur", Music.duration()).toString()
        }

        /** Sleep timer: pauses after the given minutes (0 cancels). */
        @JavascriptInterface fun sleep(minutes: Int) {
            sleepTask?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            sleepTask = null
            if (minutes <= 0) { toast("Sleep timer off."); return }
            val task = Runnable { Thread { Music.pauseNow(this@MusicActivity) }.start(); sleepTask = null }
            sleepTask = task
            Handler(Looper.getMainLooper()).postDelayed(task, minutes * 60_000L)
            toast("Music will pause in $minutes minutes.")
        }

        @JavascriptInterface fun share(id: Double) = runOnUiThread {
            try {
                val uri: Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toLong())
                val i = Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Share song"))
            } catch (e: Exception) { toast("Could not share that song.") }
        }

        @JavascriptInterface fun ringtone(id: Double) = runOnUiThread {
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toLong())
                RingtoneManager.setActualDefaultRingtoneUri(this@MusicActivity, RingtoneManager.TYPE_RINGTONE, uri)
                toast("Set as ringtone.")
            } catch (e: Exception) { toast("Allow 'Modify system settings' for Pragon to set a ringtone.") }
        }
    }

    private var sleepTask: Runnable? = null

    override fun onDestroy() {
        try { web.removeJavascriptInterface("PragonMusic"); web.stopLoading(); web.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }
}
