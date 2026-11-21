package com.pragon.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * v16: the screen-edge glow that goes with the Smart Sidebar (border glow, haze glow, ambient glow), its colour,
 * and the switches that live in the sidebar's LOOK & GLOW section. All values are in the sidebar's own prefs file.
 */
object Glow {
    private fun p(c: Context) = c.getSharedPreferences("pragon_sidebar", Context.MODE_PRIVATE)

    // switches (all off until you turn them on: a glow over every app is something you should choose)
    const val BORDER = "g_border"
    const val HAZE = "g_haze"
    const val AMBIENT = "g_ambient"
    const val SOUND = "g_sound"
    const val MATCH = "g_match"
    const val MUSIC = "show_music"

    fun get(c: Context, key: String) = p(c).getBoolean(key, key == MUSIC)
    fun set(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()
    fun any(c: Context) = get(c, BORDER) || get(c, HAZE) || get(c, AMBIENT)

    /** Blue, Cyan, Violet, Pink: same four as the preview. */
    val COLOURS = listOf(intArrayOf(0, 170, 255), intArrayOf(0, 212, 255), intArrayOf(150, 90, 255), intArrayOf(255, 80, 170))
    fun colourIndex(c: Context) = p(c).getInt("g_colour", 0).coerceIn(0, COLOURS.size - 1)
    fun setColourIndex(c: Context, i: Int) = p(c).edit().putInt("g_colour", i.coerceIn(0, COLOURS.size - 1)).apply()
    fun base(c: Context): IntArray = COLOURS[colourIndex(c)]
}

/** A full-screen, touch-through view that draws the glow. The service feeds it the colour and the sound level. */
class GlowView(ctx: Context) : View(ctx) {
    var border = false
    var haze = false
    var ambient = false
    var cr = 0f; var cg = 170f; var cb = 255f
    var level = 0f
    var phase = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun col(a: Float) = Color.argb((a * 255f).toInt().coerceIn(0, 255), cr.toInt().coerceIn(0, 255), cg.toInt().coerceIn(0, 255), cb.toInt().coerceIn(0, 255))

    /** A soft band along every edge: colour at the edge, fading to nothing `d` pixels in. */
    private fun wash(c: Canvas, w: Float, h: Float, d: Float, a: Float) {
        val from = col(a); val to = col(0f)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, d, from, to, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, d, paint)
        paint.shader = LinearGradient(0f, h, 0f, h - d, from, to, Shader.TileMode.CLAMP); c.drawRect(0f, h - d, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, d, 0f, from, to, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, d, h, paint)
        paint.shader = LinearGradient(w, 0f, w - d, 0f, from, to, Shader.TileMode.CLAMP); c.drawRect(w - d, 0f, w, h, paint)
        paint.shader = null
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val lv = level.coerceIn(0f, 1f)
        // ambient: a wide, faint wash (a phone cannot light up the room, so this lights the screen instead)
        if (ambient) wash(c, w, h, dp(150f) + lv * dp(90f), 0.14f + lv * 0.20f)
        // haze: a closer, stronger band that breathes slowly
        if (haze) {
            val breathe = 0.84f + 0.16f * kotlin.math.sin(phase)
            wash(c, w, h, dp(64f) + lv * dp(56f), (0.40f + lv * 0.30f) * breathe)
            paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
            paint.color = Color.argb((120 * breathe).toInt(), 225, 242, 255)
            rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)); c.drawRoundRect(rect, dp(26f), dp(26f), paint)
        }
        // border: a crisp coloured edge with a glow made of a few thicker, fainter strokes underneath
        if (border) {
            paint.shader = null; paint.style = Paint.Style.STROKE
            val steps = 6
            for (i in steps downTo 1) {
                val sw = dp(1.5f) + i * dp(2.2f) * (1f + lv * 0.6f)
                paint.strokeWidth = sw
                paint.color = col((0.05f + lv * 0.03f) * (steps - i + 1) / 2f)
                rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f); c.drawRoundRect(rect, dp(26f), dp(26f), paint)
            }
            paint.strokeWidth = dp(1.5f); paint.color = Color.argb(215, 150, 225, 255)
            rect.set(dp(0.75f), dp(0.75f), w - dp(0.75f), h - dp(0.75f)); c.drawRoundRect(rect, dp(26f), dp(26f), paint)
        }
    }
}
