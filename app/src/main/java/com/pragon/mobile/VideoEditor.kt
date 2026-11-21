package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * v18: Video editor (assets/video.html). The preview plays the picked video straight from the phone.
 * Export uses Media3 Transformer: keeps the chosen parts, rotates, mirrors, removes sound. Saved to
 * Movies/Pragon, which shows up in My Files.
 */
class VideoEditorActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var video: Uri? = null
    private var videoMime = "video/mp4"
    private var videoSize = 0L
    private var videoName = ""
    private var transformer: Transformer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        video = u
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { videoName = c.getString(0) ?: ""; videoSize = c.getLong(1) }
            }
            videoMime = contentResolver.getType(u) ?: "video/mp4"
        } catch (_: Exception) { }
        web.evaluateJavascript("window.onPicked && onPicked(${JSONObject.quote(videoName)})", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host == "pragon.local" && request.url.path == "/video") return serve(request.requestHeaders["Range"] ?: request.requestHeaders["range"])
                    return null
                }
            }
            addJavascriptInterface(Bridge(), "PragonVideo")
            loadUrl("file:///android_asset/video.html")
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    /** Serves the picked video to the preview, honouring Range requests so seeking works. */
    private fun serve(range: String?): WebResourceResponse? {
        val u = video ?: return null
        return try {
            val len = if (videoSize > 0) videoSize else return null
            var start = 0L; var end = len - 1
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range ?: "")
            if (m != null) {
                m.groupValues[1].toLongOrNull()?.let { start = it }
                m.groupValues[2].toLongOrNull()?.let { end = it }
            }
            end = minOf(end, len - 1)
            val stream = contentResolver.openInputStream(u) ?: return null
            var toSkip = start
            while (toSkip > 0) { val k = stream.skip(toSkip); if (k <= 0) break; toSkip -= k }
            val headers = hashMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to (end - start + 1).toString(),
                "Content-Range" to "bytes $start-$end/$len"
            )
            WebResourceResponse(videoMime, null, if (m != null) 206 else 200, if (m != null) "Partial Content" else "OK", headers,
                Limited(stream, end - start + 1))
        } catch (e: Exception) { null }
    }

    private class Limited(val s: InputStream, var left: Long) : FilterInputStream(s) {
        override fun read(): Int { if (left <= 0) return -1; val b = s.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = s.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    inner class Bridge {
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
        @JavascriptInterface fun pick() = runOnUiThread { pick.launch(arrayOf("video/*")) }
        @JavascriptInterface fun hasVideo(): Boolean = video != null
        @JavascriptInterface fun sourceUrl(): String = "https://pragon.local/video"

        /** opts: {segments:[[startMs,endMs],...], rotate:0|90|180|270, mirror:bool, mute:bool} */
        @JavascriptInterface fun export(opts: String) = runOnUiThread { startExport(opts) }
    }

    private fun startExport(opts: String) {
        val src = video ?: return toast("Pick a video first.")
        if (transformer != null) return toast("Already exporting.")
        val o = try { JSONObject(opts) } catch (e: Exception) { return toast("Nothing to export.") }
        val segs: JSONArray = o.optJSONArray("segments") ?: JSONArray()
        if (segs.length() == 0) return toast("Keep at least one part.")

        val effects = mutableListOf<Effect>()
        val rot = o.optInt("rotate", 0).toFloat()
        val mirror = o.optBoolean("mirror", false)
        if (rot != 0f || mirror) {
            effects.add(ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(rot)
                .setScale(if (mirror) -1f else 1f, 1f)
                .build())
        }
        val mute = o.optBoolean("mute", false)

        val items = ArrayList<EditedMediaItem>()
        for (i in 0 until segs.length()) {
            val pair = segs.optJSONArray(i) ?: continue
            val a = pair.optLong(0, -1L); val b = pair.optLong(1, -1L)
            if (a < 0 || b <= a) continue   // bad part (end before start): Media3 would throw
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(a)
                .setEndPositionMs(b)
                .build()
            val mi = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()
            items.add(EditedMediaItem.Builder(mi)
                .setRemoveAudio(mute)
                .setEffects(Effects(emptyList(), effects))
                .build())
        }
        if (items.isEmpty()) return toast("Those parts are empty. Check the start and end times.")

        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Pragon").apply { mkdirs() }
        val out = File(dir, "Edit_${System.currentTimeMillis()}.mp4").absolutePath
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    stopPolling()
                    transformer = null
                    saveToMovies(out)
                }
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    stopPolling()
                    transformer = null
                    try { File(out).delete() } catch (_: Exception) { }
                    web.evaluateJavascript("window.onExport && onExport('error', ${JSONObject.quote(exception.message ?: "Export failed")})", null)
                }
            })
            .build()
        transformer = t
        try {
            t.start(composition, out)
        } catch (e: Exception) {
            transformer = null
            web.evaluateJavascript("window.onExport && onExport('error', ${JSONObject.quote(e.message ?: "Could not start the export")})", null)
            return
        }
        startPolling(t)
        web.evaluateJavascript("window.onExport && onExport('started', '')", null)
    }

    /** Copies the finished file into the shared Movies/Pragon folder (gallery and My Files see it), then removes the temporary copy. */
    private fun saveToMovies(path: String) {
        Thread {
            var shown = path
            var err: String? = null
            try {
                val src = File(path)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pragon")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) err = "Could not create the file in Movies/Pragon."
                else {
                    try {
                        contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
                            ?: throw java.io.IOException("Could not write the file.")
                        src.delete()
                        shown = "Movies/Pragon/" + src.name
                    } catch (e: Exception) {
                        try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                        err = "Saved only inside the app: " + (e.message ?: "copy failed")
                    }
                }
            } catch (e: Exception) { err = "Saved only inside the app: " + (e.message ?: "copy failed") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                web.evaluateJavascript("window.onExport && onExport('done', ${JSONObject.quote(shown)})", null)
                toast(err ?: "Saved to Movies/Pragon.")
            }
        }.start()
    }

    private fun startPolling(t: Transformer) {
        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                if (transformer !== t) return
                val st = t.getProgress(holder)
                val pct = if (st == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else 0
                web.evaluateJavascript("window.onExport && onExport('progress', '$pct')", null)
                ui.postDelayed(this, 500)
            }
        }
        poll = r
        ui.post(r)
    }

    private fun stopPolling() { poll?.let { ui.removeCallbacks(it) }; poll = null }

    override fun onDestroy() {
        stopPolling()
        try { transformer?.cancel() } catch (_: Exception) { }
        transformer = null
        try { web.removeJavascriptInterface("PragonVideo"); web.stopLoading(); web.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }
}
