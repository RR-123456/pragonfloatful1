package com.pragon.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * v13: Pragon's own music player for the songs stored on the phone. Needs the "music and audio" permission and the
 * on/off switch in Settings > Music player. Controlled by voice (QuickVoice), by the AI (tool "music") and by the panel.
 * Value format for [act]: play | play:<words> | shuffle_play | pause | toggle | stop | next | previous | status |
 * shuffle:on|off | repeat:off|all|one | seek:<seconds> | on | off
 */
object Music {

    class Track(val id: Long, val title: String, val artist: String, val album: String, val ms: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val key: String get() = "$title $artist $album".lowercase()
    }

    private val lock = Any()
    private var mp: MediaPlayer? = null
    private var list: List<Track> = emptyList()
    private var listAt = 0L
    private var order: List<Int> = emptyList()   // indexes into [list] in play order
    private var at = -1                          // position inside [order]
    private var focus: AudioFocusRequest? = null
    private var resumeOnGain = false
    private var appCtx: Context? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_music", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun shuffleOn(c: Context) = sp(c).getBoolean("shuffle", false)
    fun repeatMode(c: Context) = sp(c).getString("repeat", "off") ?: "off"

    fun permission() = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasAccess(c: Context) = Perm.has(c, permission())

    fun requestAccess(c: Context) {
        val want = mutableListOf(permission())
        if (Build.VERSION.SDK_INT >= 33) want.add(android.Manifest.permission.POST_NOTIFICATIONS)
        Perm.request(c, *want.toTypedArray())
    }

    // ───────────── the song list ─────────────

    fun tracks(c: Context, force: Boolean = false): List<Track> {
        val now = System.currentTimeMillis()
        if (!force && list.isNotEmpty() && now - listAt < 60_000) return list
        if (!hasAccess(c)) return emptyList()
        val out = ArrayList<Track>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        try {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cu ->
                while (cu.moveToNext()) {
                    val artist = cu.getString(2)?.takeIf { it != "<unknown>" } ?: ""
                    out.add(Track(cu.getLong(0), cu.getString(1) ?: "Unknown", artist, cu.getString(3) ?: "", cu.getLong(4)))
                }
            }
        } catch (e: Exception) { }
        list = out; listAt = now
        return out
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.length >= 2 }

    /** The song that best matches the spoken words (title counts double), or -1. */
    private fun best(l: List<Track>, q: String): Int {
        val qw = words(q)
        if (qw.isEmpty()) return -1
        var bi = -1; var bs = 0
        l.forEachIndexed { i, t ->
            val title = words(t.title); val rest = words(t.artist + " " + t.album)
            var sc = 0
            for (w in qw) { if (w in title) sc += 3 else if (title.any { x -> x.startsWith(w) }) sc += 2 else if (w in rest) sc += 1 }
            if (title.joinToString(" ") == qw.joinToString(" ")) sc += 10
            if (sc > bs) { bs = sc; bi = i }
        }
        // at least half of the spoken words must have matched something
        return if (bs >= (qw.size + 1) / 2 * 1) bi else -1
    }

    // ───────────── playback ─────────────

    fun current(): Track? = synchronized(lock) { order.getOrNull(at)?.let { list.getOrNull(it) } }
    fun isPlaying(): Boolean = try { mp?.isPlaying == true } catch (e: Exception) { false }
    private fun pos(): Int = try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    private fun dur(): Int = try { mp?.duration ?: 0 } catch (e: Exception) { 0 }

    private fun deal(first: Int, shuffle: Boolean): List<Int> {
        val idx = list.indices.toMutableList()
        if (!shuffle) return idx
        idx.remove(first); idx.shuffle()
        return listOf(first) + idx
    }

    private fun requestFocus(c: Context): Boolean {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                synchronized(lock) {
                    val p = mp
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> { try { p?.pause() } catch (e: Exception) { }; resumeOnGain = false; refresh(c) }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (isPlaying()) { try { p?.pause() } catch (e: Exception) { }; resumeOnGain = true; refresh(c) } }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> try { p?.setVolume(0.25f, 0.25f) } catch (e: Exception) { }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            try { p?.setVolume(1f, 1f) } catch (e: Exception) { }
                            if (resumeOnGain) { resumeOnGain = false; try { p?.start() } catch (e: Exception) { }; refresh(c) }
                        }
                    }
                }
            }.build()
        focus = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus(c: Context) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun playCurrent(c: Context): CommandExecutor.Res {
        val t = current() ?: return CommandExecutor.Res(false, "Nothing to play.", label = "Music")
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        if (!requestFocus(c)) return CommandExecutor.Res(false, "Another app is using the audio right now.", label = "Music")
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(c, t.uri)
            p.setOnCompletionListener { onDone(c) }
            p.setOnErrorListener { _, _, _ -> synchronized(lock) { try { mp?.release() } catch (e: Exception) { }; mp = null; refresh(c) }; true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            try { p.release() } catch (x: Exception) { }
            return CommandExecutor.Res(false, "I couldn't play ${t.title}: ${e.message}", label = "Music")
        }
        mp = p
        refresh(c)
        return CommandExecutor.Res(true, spokenStatus(c), label = "Music")
    }

    private fun startAt(c: Context, idx: Int): CommandExecutor.Res {
        order = deal(idx, shuffleOn(c))
        at = order.indexOf(idx)
        return playCurrent(c)
    }

    private fun step(c: Context, d: Int): CommandExecutor.Res {
        if (order.isEmpty()) return CommandExecutor.Res(false, "Nothing is playing. Say play music first.", label = "Music")
        at = (at + d + order.size) % order.size
        return playCurrent(c)
    }

    private fun onDone(c: Context) {
        synchronized(lock) {
            val rep = repeatMode(c)
            if (rep == "one") playCurrent(c)
            else if (at + 1 < order.size || rep == "all") step(c, +1)
            else { try { mp?.release() } catch (e: Exception) { }; mp = null; at = -1; abandonFocus(c); refresh(c) }
        }
    }

    fun spokenStatus(c: Context): String {
        val t = current() ?: return "Nothing is playing, ${Prefs.callMe(c)}."
        val by = if (t.artist.isNotBlank()) " by ${t.artist}" else ""
        return (if (isPlaying()) "Playing " else "Paused on ") + t.title + by + "."
    }

    // ───────────── commands ─────────────

    fun act(c: Context, value: String): CommandExecutor.Res = synchronized(lock) { actLocked(c, value) }

    private fun actLocked(c: Context, value: String): CommandExecutor.Res {
        val ctx = c.applicationContext
        appCtx = ctx
        val v = value.trim()
        val cut = v.indexOf(':')
        val cmd = (if (cut >= 0) v.substring(0, cut) else v).lowercase().trim().ifEmpty { "play" }
        val arg = if (cut >= 0) v.substring(cut + 1).trim() else ""
        val me = Prefs.callMe(ctx)
        fun ok(m: String) = CommandExecutor.Res(true, m, label = "Music")
        fun no(m: String) = CommandExecutor.Res(false, m, label = "Music")

        try {
            // these work even when the player is off
            when (cmd) {
                "on" -> {
                    sp(ctx).edit().putBoolean("on", true).apply()
                    if (!hasAccess(ctx)) { requestAccess(ctx); return ok("Music player on, $me. Allow access to the music on the prompt that just opened, then say play music.") }
                    return ok("Music player on, $me. Say play music.")
                }
                "off" -> {
                    sp(ctx).edit().putBoolean("on", false).apply()
                    stopNow(ctx)
                    return ok("Music player off, $me.")
                }
                "status" -> return ok(if (enabled(ctx)) spokenStatus(ctx) else "The music player is off, $me.")
                "stop" -> { stopNow(ctx); return ok("Music stopped.") }
                "pause" -> {
                    if (!isPlaying()) return ok("Nothing is playing, $me.")
                    mp?.pause(); resumeOnGain = false; refresh(ctx); return ok("Paused.")
                }
                "shuffle" -> {
                    val on = when (arg.lowercase()) { "on" -> true; "off" -> false; else -> !shuffleOn(ctx) }
                    sp(ctx).edit().putBoolean("shuffle", on).apply()
                    val cur = order.getOrNull(at)
                    if (cur != null) { order = deal(cur, on); at = order.indexOf(cur) }
                    return ok(if (on) "Shuffle on." else "Shuffle off.")
                }
                "repeat" -> {
                    val m = when (arg.lowercase()) { "on", "all" -> "all"; "off" -> "off"; "one", "song", "this", "track" -> "one"
                        else -> when (repeatMode(ctx)) { "off" -> "all"; "all" -> "one"; else -> "off" } }
                    sp(ctx).edit().putString("repeat", m).apply()
                    return ok(when (m) { "all" -> "Repeating the whole list."; "one" -> "Repeating this song."; else -> "Repeat off." })
                }
                "seek" -> {
                    val s = arg.toIntOrNull() ?: return no("Seek to how many seconds?")
                    mp?.seekTo(s.coerceAtLeast(0) * 1000); return ok("Done.")
                }
            }

            // everything below plays something, so the player must be on and allowed
            if (!enabled(ctx)) return no("The music player is off, $me. Say turn on the music player.")
            if (!hasAccess(ctx)) { requestAccess(ctx); return no("I need permission to read the music on this phone. A prompt just opened: allow it, then ask me again.") }
            val l = tracks(ctx, force = true)
            if (l.isEmpty()) return no("I found no music files on this phone, $me.")

            if ((cmd == "play" && arg.isNotEmpty()) || cmd == "play_query" || cmd == "search") {
                val idx = best(l, arg)
                if (idx < 0) return no("I couldn't find \"${arg.trim()}\" in the music on this phone, $me.")
                return startAt(ctx, idx)
            }

            return when (cmd) {
                "play", "resume", "toggle" -> {
                    if (cmd == "toggle" && isPlaying()) { mp?.pause(); resumeOnGain = false; refresh(ctx); return ok("Paused.") }
                    val p = mp
                    if (p != null && current() != null) {
                        if (isPlaying()) return ok("It's already playing: " + spokenStatus(ctx))
                        if (!requestFocus(ctx)) return no("Another app is using the audio right now.")
                        p.start(); refresh(ctx)
                        ok(spokenStatus(ctx))
                    } else startAt(ctx, if (shuffleOn(ctx)) l.indices.random() else 0)
                }
                "shuffle_play" -> { sp(ctx).edit().putBoolean("shuffle", true).apply(); startAt(ctx, l.indices.random()) }
                "playidx" -> {
                    val i = arg.toIntOrNull() ?: return no("Which song?")
                    if (i !in l.indices) return no("That song is no longer on the phone.")
                    startAt(ctx, i)
                }
                "next" -> if (current() == null) no("Nothing is playing. Say play music first.") else step(ctx, +1)
                "previous", "prev" -> {
                    if (current() == null) return no("Nothing is playing. Say play music first.")
                    // after the first few seconds "previous" restarts the song, like every player
                    val p = mp
                    if (p != null && pos() > 4000) {
                        p.seekTo(0)
                        if (!p.isPlaying) { requestFocus(ctx); p.start() }
                        refresh(ctx)
                        ok("Back to the start of ${current()?.title}.")
                    } else step(ctx, -1)
                }
                else -> no("I don't know the music command \"$cmd\".")
            }
        } catch (e: SecurityException) {
            return no("Android refused to play that: ${e.message}")
        } catch (e: Exception) {
            return no("Music error: ${e.message}")
        }
    }

    /** v18: play a list chosen on the Music screen. start = index into tracks. */
    fun playTracks(c: Context, tracks: List<Track>, start: Int): CommandExecutor.Res = synchronized(lock) {
        val ctx = c.applicationContext
        appCtx = ctx
        if (tracks.isEmpty()) return CommandExecutor.Res(false, "Nothing to play.", label = "Music")
        sp(ctx).edit().putBoolean("on", true).apply()
        list = tracks; listAt = System.currentTimeMillis()
        startAt(ctx, start.coerceIn(0, tracks.size - 1))
    }
    fun skip(c: Context, d: Int) = synchronized(lock) { step(c.applicationContext, d) }
    /** Pause if playing, otherwise resume. */
    fun pauseToggle(c: Context) = synchronized(lock) {
        try { mp?.let { if (it.isPlaying) it.pause() else it.start() } } catch (e: Exception) { }
        refresh(c.applicationContext)
    }
    fun pauseNow(c: Context) = synchronized(lock) {
        try { mp?.let { if (it.isPlaying) it.pause() } } catch (e: Exception) { }
        refresh(c.applicationContext)
    }
    fun seekTo(ms: Int) = synchronized(lock) { try { mp?.seekTo(ms) } catch (e: Exception) { } }
    fun setSpeed(f: Float, pitch: Float) = synchronized(lock) {
        try {
            val player = mp
            if (player != null) {
                // Android starts a paused player when its speed is set; put it back to paused.
                val wasPlaying = try { player.isPlaying } catch (e: Exception) { false }
                player.playbackParams = android.media.PlaybackParams().setSpeed(f.coerceIn(0.25f, 4f)).setPitch(pitch.coerceIn(0.25f, 4f))
                if (!wasPlaying) try { player.pause() } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }
    fun position(): Int = pos()
    fun duration(): Int = dur()

    private fun stopNow(c: Context) {
        try { mp?.stop() } catch (e: Exception) { }
        try { mp?.release() } catch (e: Exception) { }
        mp = null; at = -1; order = emptyList(); resumeOnGain = false
        abandonFocus(c)
        refresh(c)
    }

    // ───────────── the panel ─────────────

    fun json(c: Context, query: String): String {
        val o = JSONObject()
        val l = if (enabled(c) && hasAccess(c)) tracks(c) else emptyList()
        o.put("on", enabled(c)).put("access", hasAccess(c)).put("count", l.size)
            .put("shuffle", shuffleOn(c)).put("repeat", repeatMode(c))
            .put("playing", isPlaying()).put("pos", pos() / 1000).put("dur", dur() / 1000)
        val t = current()
        o.put("title", t?.title ?: "").put("artist", t?.artist ?: "")
        val q = words(query)
        val arr = JSONArray()
        var n = 0
        l.forEachIndexed { i, tr ->
            if (n >= 150) return@forEachIndexed
            if (q.isNotEmpty() && !q.all { w -> tr.key.contains(w) }) return@forEachIndexed
            arr.put(JSONObject().put("i", i).put("t", tr.title).put("a", tr.artist)); n++
        }
        return o.put("tracks", arr).toString()
    }

    // ───────────── notification and service ─────────────

    private const val CH = "pragon_music"
    const val NOTIF_ID = 4113

    /** Keeps the foreground notification (with Previous / Pause / Next) in step with the player. */
    fun refresh(c: Context) {
        val ctx = c.applicationContext
        try {
            if (current() != null) ctx.startForegroundService(Intent(ctx, MusicService::class.java).setAction("sync"))
            else ctx.stopService(Intent(ctx, MusicService::class.java))
        } catch (e: Exception) { }
    }

    fun notification(c: Context): Notification {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Music player", NotificationManager.IMPORTANCE_LOW))
        fun pi(action: String, code: Int) = PendingIntent.getService(c, code,
            Intent(c, MusicService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(icon: Int, title: String, action: String, code: Int) =
            Notification.Action.Builder(Icon.createWithResource(c, icon), title, pi(action, code)).build()
        val t = current()
        val playing = isPlaying()
        return Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "Pragon music")
            .setContentText(t?.artist?.ifBlank { null } ?: "Music player")
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", "previous", 1))
            .addAction(act(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", "toggle", 2))
            .addAction(act(android.R.drawable.ic_media_next, "Next", "next", 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Stop", "stop", 4))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}

/** Foreground service that keeps the music playing with the screen off and shows the notification buttons. */
class MusicService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // must show the notification at once, whatever the command was
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(Music.NOTIF_ID, Music.notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(Music.NOTIF_ID, Music.notification(this))
        } catch (e: Exception) { }
        val a = intent?.action ?: "sync"
        if (a != "sync") {
            Thread {
                Music.act(this, a)
                if (Music.current() == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }.start()
        }
        return START_NOT_STICKY
    }
}
