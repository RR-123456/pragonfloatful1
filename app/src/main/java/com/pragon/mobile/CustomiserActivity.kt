package com.pragon.mobile

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * UI customiser: the PC orb page with its "UI customiser" widget open (UI editor, Orb style, Orb tuning,
 * Colour & glass, Cluster maker, Motion combos, Click sound, Core logo ...). Opened from Settings.
 * Everything saved here is read by the floating bubble, which reloads when this screen closes.
 */
class CustomiserActivity : AppCompatActivity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = OrbWeb.create(this, bubble = false)
        setContentView(web)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onDestroy() {
        FloatService.reloadOrb()      // the bubble picks up what was just saved
        web?.destroy(); web = null
        super.onDestroy()
    }
}
