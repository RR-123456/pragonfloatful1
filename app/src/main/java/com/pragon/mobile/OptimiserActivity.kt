package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * v15: Phone optimiser (assets/optimiser.html): junk cleaner, app cache cleaner and memory boost.
 * The page does the drawing; every number and every deletion comes from [Optimiser]. Nothing here uses the network.
 */
class OptimiserActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "PragonOpt")
            loadUrl("file:///android_asset/optimiser.html" + (intent?.getStringExtra("tab")?.let { "#$it" } ?: ""))
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.optBack&&optBack())?true:false") { r -> if (r != "true") finish() }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getStringExtra("tab")
        if (::web.isInitialized && tab != null) web.evaluateJavascript("window.optGo&&optGo('$tab')", null)
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.optResume&&optResume()", null)
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    inner class Bridge {
        @JavascriptInterface fun home(): String = Optimiser.homeJson(this@OptimiserActivity)
        @JavascriptInterface fun close() { runOnUiThread { finish() } }

        @JavascriptInterface fun askAllFiles() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) { toast("Open Settings > Apps > Pragon > Files and media and allow all files.") }
                }
            }
        }
        @JavascriptInterface fun askUsage() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) { toast("Open Settings > Apps > Special access > Usage access.") } }
            }
        }
        @JavascriptInterface fun askA11y() {
            runOnUiThread { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { toast("Open Settings > Accessibility.") } }
        }

        // junk
        @JavascriptInterface fun startScan(): String {
            if (!Optimiser.hasAll()) return "perm"
            Optimiser.startScan(this@OptimiserActivity); return ""
        }
        @JavascriptInterface fun cancelScan() { Optimiser.scan.cancel = true }
        @JavascriptInterface fun scanState(): String = Optimiser.stateJson()
        @JavascriptInterface fun scanResult(): String = Optimiser.resultJson()
        @JavascriptInterface fun clean(request: String): String = Optimiser.clean(this@OptimiserActivity, request)

        // app cache
        @JavascriptInterface fun apps(): String = Optimiser.appsJson(this@OptimiserActivity)
        @JavascriptInterface fun clearCaches(pkgs: String): String = Optimiser.clearCaches(this@OptimiserActivity, pkgs)
        @JavascriptInterface fun cacheState(): String = Optimiser.cacheJson()
        @JavascriptInterface fun cancelCache() { Optimiser.cache.cancel = true }
        @JavascriptInterface fun appInfo(pkg: String) {
            runOnUiThread { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) } catch (_: Exception) { } }
        }

        // boost
        @JavascriptInterface fun mem(): String = Optimiser.memJson(this@OptimiserActivity)
        @JavascriptInterface fun keepList(): String = org.json.JSONArray(Optimiser.keepSet(this@OptimiserActivity).toList()).toString()
        @JavascriptInterface fun setKeep(pkg: String, on: Boolean) { Optimiser.setKeep(this@OptimiserActivity, pkg, on) }
        @JavascriptInterface fun boost(): String = Optimiser.boost(this@OptimiserActivity)

        @JavascriptInterface fun openFiles() { runOnUiThread { startActivity(Intent(this@OptimiserActivity, FilesActivity::class.java)) } }
    }
}
