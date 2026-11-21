package com.pragon.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The PC orb (assets/orb/pragon_orb.html, the same page the desktop orb runs) in a WebView.
 * The floating bubble and the UI customiser both load this one page, so they share the page's
 * saved settings (orb style, colours, glow, click sound, core logo ...): change something in the
 * customiser and the bubble shows it after a reload.
 */
object OrbWeb {
    const val URL = "file:///android_asset/orb/pragon_orb.html"

    /** Same set-up the desktop wrapper (orb.html) applies to the orb page: no dark body, no edge haze, no dot grid, no HUD bar. */
    private const val BUBBLE_INIT = """
(function(){
  var s=document.createElement('style');
  s.textContent='html,body,#stage{background:transparent!important}#edge{display:none!important}';
  document.head.appendChild(s);
  var n=0;
  (function go(){
    if(!window.orb){ if(n++<80) setTimeout(go,100); return; }
    try{ orb.setTransparent(true); orb.setPart('bar',false); orb.move(0,0,1.25,0.01); }catch(e){}
  })();
})();
"""

    /** Opens the page's own "UI customiser" widget (the page listens for this message). */
    private const val OPEN_CUSTOMISER = """
(function(){
  var n=0;
  (function go(){
    if(!window.orb){ if(n++<80) setTimeout(go,100); return; }
    setTimeout(function(){ window.postMessage({pragonCmd:'customiser',open:true},'*'); },300);
  })();
})();
"""

    /**
     * @param bubble true = transparent orb only (the floating bubble); false = the full page with the UI customiser opened.
     * @param onLoaded called on the main thread each time the page has finished loading (also after a reload).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, bubble: Boolean, onLoaded: (WebView) -> Unit = {}): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // the customiser saves its choices in the page's localStorage
            mediaPlaybackRequiresUserGesture = false // orb sounds
            allowFileAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.setBackgroundColor(if (bubble) Color.TRANSPARENT else Color.BLACK)
        w.webChromeClient = WebChromeClient()        // camera/microphone requests are not granted: the orb stays a plain orb
        w.webViewClient = object : WebViewClient() {
            // Stay on the orb page: nothing inside it should navigate the bubble somewhere else.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(if (bubble) BUBBLE_INIT else OPEN_CUSTOMISER, null)
                onLoaded(view)
            }
        }
        w.loadUrl(URL)
        return w
    }

    /** WebView must be touched on the main thread; this is safe to call from any thread. */
    fun call(w: WebView?, js: String) {
        if (w == null) return
        val run = Runnable { try { w.evaluateJavascript("try{$js}catch(e){}", null) } catch (_: Exception) { } }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) run.run()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(run)
    }
}
