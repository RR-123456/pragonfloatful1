package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * v18: Tools hub (assets/tools.html), opened from the sidebar's NAVIGATE row.
 * Every tool hands off to the phone's own app with an intent, so nothing is sent by Pragon:
 * Map / Directions / Rapido / Uber / Ola, Calendar (new event, pre-filled), Camera (system camera,
 * the photo is saved to Pictures/Pragon as soon as it is taken), Calculator (in the page),
 * Google Lens, Messages (new SMS, pre-filled), Clock (set an alarm or timer).
 */
open class ToolsActivity : AppCompatActivity() {

    /** Subclasses (one screen per feature) override this to open just one panel. */
    protected open fun startPanel(): String = intent?.getStringExtra("panel").orEmpty()
    /** Subclasses override this to run one action (camera, lens) when the screen opens. */
    protected open fun startAction(): String = intent?.getStringExtra("action").orEmpty()

    private lateinit var web: WebView
    private var pendingPhoto: Uri? = null
    private var started = false

    /** Camera and Lens screens have no panel of their own: close them once the hand-off is finished. */
    private fun finishIfActionScreen() { if (startAction().isNotEmpty()) finish() }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else { toast("Camera permission is needed to take a photo."); finishIfActionScreen() }
    }

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = pendingPhoto
        pendingPhoto = null
        if (uri == null) { finishIfActionScreen(); return@registerForActivityResult }
        // The camera writes straight into MediaStore. If the user backed out before the shot, drop the empty entry.
        val ok = res.resultCode == RESULT_OK || isNonEmpty(uri)
        if (ok) toast("Photo saved to Pictures/Pragon")
        else { try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }; toast("No photo taken") }
        finishIfActionScreen()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean("started", false) ?: false
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    openStart()
                }
            }
            addJavascriptInterface(Bridge(), "PragonTools")
            loadUrl("file:///android_asset/tools.html")
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    /** Runs the action (camera, lens) or shows the one panel this screen is for. */
    private fun openStart() {
        val panel = startPanel()
        when (startAction()) {
            "camera", "lens" -> {
                // onPageFinished can fire more than once; the hand-off must run only once per screen.
                if (started) return
                started = true
                if (startAction() == "camera") Bridge().camera() else Bridge().lens()
                return
            }
        }
        if (panel.isNotEmpty() && panel.all { it.isLetterOrDigit() }) web.evaluateJavascript("only('$panel')", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("started", started)
    }

    override fun onDestroy() {
        try { web.removeJavascriptInterface("PragonTools"); web.stopLoading(); web.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }

    /** Opens the system camera once CAMERA is granted (the manifest declares it, so Android refuses the capture intent without it). */
    private fun takePhoto() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Pragon_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pragon")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) { toast("Could not create a photo file."); finishIfActionScreen(); return }
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (i.resolveActivity(packageManager) == null) {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                toast("No camera app found."); finishIfActionScreen(); return
            }
            pendingPhoto = uri
            try { camera.launch(i) } catch (e: Exception) {
                pendingPhoto = null
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                toast("Could not open the camera."); finishIfActionScreen()
            }
        } catch (_: Exception) { toast("Could not open the camera."); finishIfActionScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun isNonEmpty(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0 } ?: false
    } catch (_: Exception) { false }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    /** Starts an intent; if no app takes it, says so instead of crashing. */
    private fun go(i: Intent, fail: String): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { toast(fail); false }
    catch (_: Exception) { toast(fail); false }

    private fun launchPkg(pkg: String, name: String, store: Boolean = true) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) { go(i, "Could not open $name."); return }
        if (store) go(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")), "$name is not installed.")
        else toast("$name is not installed.")
    }

    inner class Bridge {
        @JavascriptInterface fun close() { runOnUiThread { finish() } }

        /** Opens the map at an address or place name, or at "lat,lng". */
        @JavascriptInterface fun map(query: String) {
            val q = query.trim()
            if (q.isEmpty()) return toast("Type an address or place first.")
            runOnUiThread {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))
                i.setPackage("com.google.android.apps.maps")
                if (packageManager.resolveActivity(i, 0) == null) i.setPackage(null)
                go(i, "No map app found.")
            }
        }

        /** Turn-by-turn directions to a destination (Google Maps if installed). mode: d, w, b, l. */
        @JavascriptInterface fun directions(dest: String, mode: String) {
            val d = dest.trim()
            if (d.isEmpty()) return toast("Type a destination first.")
            val m = if (mode in listOf("d", "w", "b", "l")) mode else "d"
            runOnUiThread {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(d) + "&mode=" + m))
                i.setPackage("com.google.android.apps.maps")
                if (packageManager.resolveActivity(i, 0) != null) go(i, "Could not open directions.")
                else go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(d))), "No map app found.")
            }
        }

        /** Opens a ride app: app = rapido | uber | ola. Opens Play Store listing if it is not installed. */
        @JavascriptInterface fun ride(app: String) = runOnUiThread {
            when (app) {
                "rapido" -> launchPkg("com.rapido.passenger", "Rapido")
                "uber" -> launchPkg("com.ubercab", "Uber")
                "ola" -> launchPkg("com.olacabs.customer", "Ola")
                else -> toast("Unknown ride app.")
            }
        }

        /** New calendar event, pre-filled. start/end are epoch milliseconds as text. */
        @JavascriptInterface fun calendar(title: String, startMs: String, endMs: String, location: String, notes: String) {
            val s = startMs.toLongOrNull() ?: return toast("Pick a start time.")
            val e = endMs.toLongOrNull() ?: (s + 3_600_000L)
            runOnUiThread {
                val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e)
                    .putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Event" })
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                    .putExtra(CalendarContract.Events.DESCRIPTION, notes)
                go(i, "No calendar app found.")
            }
        }

        /** System camera. The photo is written to MediaStore (Pictures/Pragon) when the shot is taken, so it is saved even if the user never presses Done. */
        @JavascriptInterface fun camera() = runOnUiThread {
            if (ContextCompat.checkSelfPermission(this@ToolsActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }

        /** Google Lens (the app if installed, otherwise lens.google.com in the browser). */
        @JavascriptInterface fun lens() = runOnUiThread {
            val i = packageManager.getLaunchIntentForPackage("com.google.ar.lens")
            if (i != null) go(i, "Could not open Google Lens.")
            else go(Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/")), "Could not open Google Lens.")
            finishIfActionScreen()
        }

        /** New SMS to a number, pre-filled. Nothing is sent until the user presses send. */
        @JavascriptInterface fun message(number: String, body: String) = runOnUiThread {
            val n = number.trim()
            val i = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null)).putExtra("sms_body", body)
            go(i, "No messages app found.")
        }

        /** Alarm at hour:minute (24h) with a label. days: "" = ring once, or CSV of Android weekday numbers (1 = Sunday … 7 = Saturday). */
        @JavascriptInterface fun alarm(hour: Int, minute: Int, label: String, days: String) = runOnUiThread {
            if (hour !in 0..23 || minute !in 0..59) return@runOnUiThread toast("Check the time.")
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            val list = days.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
            if (list.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(list))
            go(i, "No clock app found.")
        }

        /** Monitor settings as JSON: {"gmail":{"on":true,"from":"GitHub"},...} */
        @JavascriptInterface fun monitors(): String = NotifyRules.json(this@ToolsActivity).toString()

        /** Saves one monitor. from = sender filter ("" = everyone). */
        @JavascriptInterface fun setMonitor(id: String, on: Boolean, from: String) = NotifyRules.set(this@ToolsActivity, id, on, from)

        /** True when Pragon has Notification access. */
        @JavascriptInterface fun notifyAccess(): Boolean {
            val enabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
            return enabled.split(":").any { it.startsWith(packageName) }
        }

        @JavascriptInterface fun openNotifyAccess() = runOnUiThread {
            go(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "Could not open notification access.")
        }

        /** Drafts text in the background, then calls onDraft(text, ok) in the page. */
        @JavascriptInterface fun draft(kind: String, tone: String, input: String) {
            Thread {
                val (ok, text) = try { Drafter.draft(this@ToolsActivity, kind, tone, input) } catch (e: Exception) { false to "Could not draft." }
                runOnUiThread { if (!isFinishing && !isDestroyed) web.evaluateJavascript("window.onDraft && onDraft(${org.json.JSONObject.quote(text)}, $ok)", null) }
            }.start()
        }

        /** Share text with any app (email, messages, notes…). */
        @JavascriptInterface fun share(text: String) = runOnUiThread {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            go(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Could not share.")
        }

        /** Opens the video editor. */
        @JavascriptInterface fun openVideo() = runOnUiThread {
            go(Intent(this@ToolsActivity, VideoEditorActivity::class.java), "Could not open the video editor.")
        }

        /** Opens the Music screen. */
        @JavascriptInterface fun openMusic() = runOnUiThread {
            go(Intent(this@ToolsActivity, MusicActivity::class.java), "Could not open Music.")
        }

        /** Opens the phone's clock app (for world clock and stopwatch). */
        @JavascriptInterface fun clockApp() = runOnUiThread {
            go(Intent(AlarmClock.ACTION_SHOW_ALARMS), "No clock app found.")
        }

        /** Timer for a number of seconds. */
        @JavascriptInterface fun timer(seconds: Int, label: String) = runOnUiThread {
            if (seconds <= 0) return@runOnUiThread toast("Set a time first.")
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            go(i, "No clock app found.")
        }
    }
}
