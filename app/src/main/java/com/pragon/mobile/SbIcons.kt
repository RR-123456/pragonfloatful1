package com.pragon.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.PathParser

/** v19: the preview's line icons (generated from its SVG set), drawn natively with the same gradient stroke. */
class SbIcon(ctx: Context, private val name: String, private val mode: Int = CYAN) : View(ctx) {
    companion object {
        const val CYAN = 0; const val GRAY = 1; const val LIGHT = 2; const val RED = 3
        private val cache = HashMap<String, List<Pair<Path, Boolean>>>()
        private fun paths(n: String): List<Pair<Path, Boolean>> = cache.getOrPut(n) {
            (DATA[n] ?: emptyArray<String>()).mapNotNull { raw ->
                val fill = raw.startsWith("F:")
                try { PathParser.createPathFromPathData(raw.substring(2))?.let { it to fill } } catch (e: Throwable) { null }
            }
        }
        private val DATA: Map<String, Array<String>> = mapOf(
            "gear" to arrayOf("S:M 9 12 A 3 3 0 1 0 15 12 A 3 3 0 1 0 9 12 Z", "S:M 12 2 v 3 M 12 19 v 3 M 2 12 h 3 M 19 12 h 3 M 4.9 4.9 l 2.1 2.1 M 17 17 l 2.1 2.1 M 4.9 19.1 L 7 17 M 17 7 l 2.1 -2.1"),
            "plus" to arrayOf("S:M 12 5 v 14 M 5 12 h 14"),
            "talk" to arrayOf("S:M 5.5 7 A 3.5 3.5 0 1 0 12.5 7 A 3.5 3.5 0 1 0 5.5 7 Z", "S:M 2.5 21 v -1.5 A 5.5 5.5 0 0 1 8 14 h 2 a 5.5 5.5 0 0 1 5.5 5.5 V 21", "S:M 16.5 8.5 a 3 3 0 0 1 0 3.5 M 19.5 5.5 a 7 7 0 0 1 0 9.5"),
            "edit" to arrayOf("S:M 4 20 h 4 L 19 9 l -4 -4 L 4 16 z", "S:M 13.5 6.5 l 4 4"),
            "mic" to arrayOf("S:M 12 2 H 12 A 3 3 0 0 1 15 5 V 11 A 3 3 0 0 1 12 14 H 12 A 3 3 0 0 1 9 11 V 5 A 3 3 0 0 1 12 2 Z", "S:M 5 11 a 7 7 0 0 0 14 0 M 12 18 v 4"),
            "x" to arrayOf("S:M 6 6 l 12 12 M 18 6 L 6 18"),
            "torch" to arrayOf("S:M 9 2 h 6 l 1 5 H 8 z M 8 7 h 8 v 4 l -1 2 v 9 H 9 v -9 l -1 -2 z"),
            "vol" to arrayOf("S:M 4 9 v 6 h 4 l 5 4 V 5 L 8 9 z M 16 9 a 4 4 0 0 1 0 6 M 18.5 6.5 a 8 8 0 0 1 0 11"),
            "vdn" to arrayOf("S:M 4 9 v 6 h 4 l 5 4 V 5 L 8 9 z M 16 10 a 3 3 0 0 1 0 4"),
            "mute" to arrayOf("S:M 4 9 v 6 h 4 l 5 4 V 5 L 8 9 z M 17 9 l 5 6 M 22 9 l -5 6"),
            "sun" to arrayOf("S:M 8 12 A 4 4 0 1 0 16 12 A 4 4 0 1 0 8 12 Z", "S:M 12 2 v 2 M 12 20 v 2 M 2 12 h 2 M 20 12 h 2 M 5 5 l 1.5 1.5 M 17.5 17.5 L 19 19 M 5 19 l 1.5 -1.5 M 17.5 6.5 L 19 5"),
            "sund" to arrayOf("S:M 8 12 A 4 4 0 1 0 16 12 A 4 4 0 1 0 8 12 Z", "S:M 12 3 v 1 M 12 20 v 1 M 3 12 h 1 M 20 12 h 1"),
            "shot" to arrayOf("S:M 6 6 H 18 A 3 3 0 0 1 21 9 V 17 A 3 3 0 0 1 18 20 H 6 A 3 3 0 0 1 3 17 V 9 A 3 3 0 0 1 6 6 Z", "S:M 8.5 13 A 3.5 3.5 0 1 0 15.5 13 A 3.5 3.5 0 1 0 8.5 13 Z", "S:M 8 6 l 1.5 -3 h 5 L 16 6"),
            "lock" to arrayOf("S:M 8 11 H 16 A 3 3 0 0 1 19 14 V 18 A 3 3 0 0 1 16 21 H 8 A 3 3 0 0 1 5 18 V 14 A 3 3 0 0 1 8 11 Z", "S:M 8 11 V 8 a 4 4 0 0 1 8 0 v 3"),
            "back" to arrayOf("S:M 15 5 l -7 7 7 7"),
            "home" to arrayOf("S:M 3 11 l 9 -8 9 8 M 5 10 v 10 h 5 v -6 h 4 v 6 h 5 V 10"),
            "rec" to arrayOf("S:M 8 5 H 16 A 3 3 0 0 1 19 8 V 16 A 3 3 0 0 1 16 19 H 8 A 3 3 0 0 1 5 16 V 8 A 3 3 0 0 1 8 5 Z"),
            "pw" to arrayOf("S:M 12 3 v 9 M 6.5 6.5 a 8 8 0 1 0 11 0"),
            "wifi" to arrayOf("S:M 2 9 a 15 15 0 0 1 20 0 M 5 12.5 a 10 10 0 0 1 14 0 M 8.5 16 a 5 5 0 0 1 7 0", "S:M 11.2 19.5 A 0.8 0.8 0 1 0 12.8 19.5 A 0.8 0.8 0 1 0 11.2 19.5 Z"),
            "bt" to arrayOf("S:M 6 7 l 11 10 -5 4 V 3 l 5 4 L 6 17"),
            "data" to arrayOf("S:M 7 20 V 10 M 12 20 V 5 M 17 20 v -7"),
            "plane" to arrayOf("S:M 21 15 l -8 -4 V 4 a 1 1 0 0 0 -2 0 v 7 l -8 4 v 2 l 8 -2 v 4 l -2 1 v 1 l 3 -.5 3 .5 v -1 l -2 -1 v -4 l 8 2 z"),
            "rot" to arrayOf("S:M 20 12 a 8 8 0 0 1 -14 5 M 4 12 a 8 8 0 0 1 14 -5 M 18 3 v 4 h -4 M 6 21 v -4 h 4"),
            "dnd" to arrayOf("S:M 3 12 A 9 9 0 1 0 21 12 A 9 9 0 1 0 3 12 Z", "S:M 8 12 h 8"),
            "bolt" to arrayOf("S:M 13 2 L 4 14 h 7 l -1 8 9 -12 h -7 z"),
            "work" to arrayOf("S:M 5 7 H 19 A 2 2 0 0 1 21 9 V 18 A 2 2 0 0 1 19 20 H 5 A 2 2 0 0 1 3 18 V 9 A 2 2 0 0 1 5 7 Z", "S:M 9 7 V 4 h 6 v 3"),
            "note" to arrayOf("S:M 9 18 V 5 l 11 -2 v 13", "S:M 3 18 A 3 3 0 1 0 9 18 A 3 3 0 1 0 3 18 Z", "S:M 14 16 A 3 3 0 1 0 20 16 A 3 3 0 1 0 14 16 Z"),
            "scr" to arrayOf("S:M 3 12 A 9 9 0 1 0 21 12 A 9 9 0 1 0 3 12 Z", "F:M 9 12 A 3 3 0 1 0 15 12 A 3 3 0 1 0 9 12 Z"),
            "dev" to arrayOf("S:M 5 3 H 9 A 2 2 0 0 1 11 5 V 9 A 2 2 0 0 1 9 11 H 5 A 2 2 0 0 1 3 9 V 5 A 2 2 0 0 1 5 3 Z", "S:M 15 3 H 19 A 2 2 0 0 1 21 5 V 9 A 2 2 0 0 1 19 11 H 15 A 2 2 0 0 1 13 9 V 5 A 2 2 0 0 1 15 3 Z", "S:M 5 13 H 9 A 2 2 0 0 1 11 15 V 19 A 2 2 0 0 1 9 21 H 5 A 2 2 0 0 1 3 19 V 15 A 2 2 0 0 1 5 13 Z", "S:M 15 13 H 19 A 2 2 0 0 1 21 15 V 19 A 2 2 0 0 1 19 21 H 15 A 2 2 0 0 1 13 19 V 15 A 2 2 0 0 1 15 13 Z"),
            "bc" to arrayOf("S:M 5 20 l 4 -16 M 15 4 l 4 16 M 3 9 h 18 M 2 15 h 18"),
            "clean" to arrayOf("S:M 4 20 l 7 -7 M 13 3 l 8 8 -4 1 -5 -5 z M 10 12 l -3 3"),
            "boost" to arrayOf("S:M 12 2 c 4 3 6 7 5 12 l -2 2 H 9 l -2 -2 c -1 -5 1 -9 5 -12 z M 9 18 l -2 4 M 15 18 l 2 4", "S:M 10.4 10 A 1.6 1.6 0 1 0 13.6 10 A 1.6 1.6 0 1 0 10.4 10 Z"),
            "tool" to arrayOf("S:M 14 6 a 4 4 0 0 0 5 5 l -9 9 a 2.5 2.5 0 0 1 -4 -4 z M 3 3 l 4 4"),
            "play" to arrayOf("F:M 7 4 l 13 8 -13 8 z"),
            "pause" to arrayOf("S:M 8 5 v 14 M 16 5 v 14"),
            "next" to arrayOf("S:M 5 5 l 10 7 -10 7 z M 19 5 v 14"),
            "prev" to arrayOf("S:M 19 5 l -10 7 10 7 z M 5 5 v 14"),
            "kb" to arrayOf("S:M 4 6 H 20 A 2 2 0 0 1 22 8 V 16 A 2 2 0 0 1 20 18 H 4 A 2 2 0 0 1 2 16 V 8 A 2 2 0 0 1 4 6 Z", "S:M 6 10 h .01 M 10 10 h .01 M 14 10 h .01 M 18 10 h .01 M 7 14 h 10"),
            "read" to arrayOf("S:M 4 9 v 6 h 4 l 5 4 V 5 L 8 9 z M 17 8 a 5 5 0 0 1 0 8"),
            "bat" to arrayOf("S:M 5 8 H 17 A 2 2 0 0 1 19 10 V 15 A 2 2 0 0 1 17 17 H 5 A 2 2 0 0 1 3 15 V 10 A 2 2 0 0 1 5 8 Z", "S:M 22 11 v 3 M 6 11 v 3"),
            "files" to arrayOf("S:M 3 6 a 2 2 0 0 1 2 -2 h 4 l 2 2 h 8 a 2 2 0 0 1 2 2 v 10 a 2 2 0 0 1 -2 2 H 5 a 2 2 0 0 1 -2 -2 z"),
            "apps" to arrayOf("S:M 5 3 H 8 A 2 2 0 0 1 10 5 V 8 A 2 2 0 0 1 8 10 H 5 A 2 2 0 0 1 3 8 V 5 A 2 2 0 0 1 5 3 Z", "S:M 16 3 H 19 A 2 2 0 0 1 21 5 V 8 A 2 2 0 0 1 19 10 H 16 A 2 2 0 0 1 14 8 V 5 A 2 2 0 0 1 16 3 Z", "S:M 5 14 H 8 A 2 2 0 0 1 10 16 V 19 A 2 2 0 0 1 8 21 H 5 A 2 2 0 0 1 3 19 V 16 A 2 2 0 0 1 5 14 Z", "S:M 16 14 H 19 A 2 2 0 0 1 21 16 V 19 A 2 2 0 0 1 19 21 H 16 A 2 2 0 0 1 14 19 V 16 A 2 2 0 0 1 16 14 Z"),
            "grid" to arrayOf("S:M 4 6 h 16 M 4 12 h 16 M 4 18 h 16"),
            "macro" to arrayOf("S:M 3 12 A 9 9 0 1 0 21 12 A 9 9 0 1 0 3 12 Z", "F:M 10 8 l 6 4 -6 4 z"),
            "ctl" to arrayOf("S:M 4 6 h 10 M 18 6 h 2 M 4 12 h 4 M 12 12 h 8 M 4 18 h 12 M 20 18 h 0", "S:M 14 6 A 2 2 0 1 0 18 6 A 2 2 0 1 0 14 6 Z", "S:M 8 12 A 2 2 0 1 0 12 12 A 2 2 0 1 0 8 12 Z", "S:M 16 18 A 2 2 0 1 0 20 18 A 2 2 0 1 0 16 18 Z"),
        )
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init {
        when (mode) {
            CYAN -> paint.shader = LinearGradient(3f, 3f, 21f, 21f, intArrayOf(0xFF9BEFFF.toInt(), 0xFF00D4FF.toInt(), 0xFF0A8FB5.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            GRAY -> paint.shader = LinearGradient(3f, 3f, 21f, 21f, intArrayOf(0xFFB7BFCB.toInt(), 0xFF6B7686.toInt()), null, Shader.TileMode.CLAMP)
            LIGHT -> paint.color = 0xFFE6FAFF.toInt()
            else -> paint.color = 0xFFFCA5A5.toInt()
        }
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        c.scale(s, s)
        for ((p, fill) in paths(name)) {
            paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            c.drawPath(p, paint)
        }
        c.restore()
    }
}

/** v19: the notched blade + chevron edge handle from the preview. */
class SbHandle(ctx: Context, private val left: Boolean) : View(ctx) {
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; strokeJoin = Paint.Join.MITER }
    private val blade = Path().apply { moveTo(15f, 0f); lineTo(15.7f, 60f); lineTo(15f, 120f); lineTo(14.3f, 60f); close() }
    private val chev = Path().apply { moveTo(10.5f, 53f); lineTo(6.5f, 60f); lineTo(10.5f, 67f) }
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val cols = intArrayOf(0x0000697F, 0xFF00697F.toInt(), 0xFF808D9E.toInt(), 0xFF00697F.toInt(), 0x0000697F)
        val pos = floatArrayOf(0f, .28f, .5f, .72f, 1f)
        val g = LinearGradient(0f, 0f, 0f, 120f, cols, pos, Shader.TileMode.CLAMP)
        fillP.shader = g; lineP.shader = g
    }
    override fun onDraw(c: Canvas) {
        c.save()
        if (left) c.scale(-1f, 1f, width / 2f, 0f)
        c.scale(width / 18f, height / 120f)
        fillP.setShadowLayer(5f, 0f, 0f, 0xB300D4FF.toInt()); lineP.setShadowLayer(5f, 0f, 0f, 0xB300D4FF.toInt())
        c.drawPath(blade, fillP); c.drawPath(chev, lineP)
        c.restore()
    }
}
