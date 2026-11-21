package com.pragon.mobile

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The phone's own Pragon: a chat screen that works with no PC. It is shown
 * whenever the app is not showing the PC's PhoneView page. Text and voice in,
 * spoken replies out, and Gemini can operate the phone through phone_control.
 *
 * Design: "Pragon Chat" (black, Newsreader, silver title, cyan assistant bubbles, dark glass
 * user bubbles, logo watermark, two-row dock). Keeps: mode and engine chips, float, attach,
 * mic (Google speech dialog), mute and settings.
 */
class StandaloneView(
    context: Context,
    private val onMic: () -> Unit,
    private val onSettings: () -> Unit,
    private val onAttach: () -> Unit,
    private val onFloat: () -> Unit = {},
) : LinearLayout(context) {

    val statusLine: TextView
    private val list: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val chips: TextView
    private val floatBtn: ImageView
    private val clearBtn: ImageView
    private val engineChip: TextView
    private val modeChip: TextView
    private val wmView: ImageView
    private val wmLabel: TextView
    private val pending = mutableListOf<Attachment>()
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var confirmClear = false
    private var typingRow: View? = null
    private val typingAnims = mutableListOf<ObjectAnimator>()
    private var floatBreath: ObjectAnimator? = null

    private val cyan = 0xFF00D4FF.toInt()
    private val silver = 0xFFA9B0BC.toInt()
    private val iconGrey = 0xFFB6C4D4.toInt()

    private val wmLevels = floatArrayOf(1f, 0.7f, 0.4f, 0.15f)
    private val wmNames = arrayOf("100%", "70%", "40%", "15%")
    private val uiPrefs by lazy { context.getSharedPreferences("pragon_ui", Context.MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- Newsreader (variable weight) from assets/fonts; falls back to the system serif ----
    private fun font(weight: Int): Typeface = try {
        Typeface.Builder(context.assets, "fonts/newsreader.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build() ?: Typeface.SERIF
    } catch (e: Throwable) { Typeface.SERIF }

    private val fRegular: Typeface by lazy { font(400) }
    private val fMedium: Typeface by lazy { font(500) }
    private val fSemi: Typeface by lazy { font(600) }
    private val fItalic: Typeface by lazy {
        try { Typeface.createFromAsset(context.assets, "fonts/newsreader_italic.ttf") } catch (e: Throwable) { Typeface.create(Typeface.SERIF, Typeface.ITALIC) }
    }

    // ---- surfaces ----
    private fun grad(radius: Int, top: Int, bottom: Int, stroke: Int, strokeDp: Int = 1) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeDp), stroke)
        }

    /** "ash": the dark metal surface of the secondary buttons. */
    private fun ash(radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0C0E12.toInt(), 0xFF040506.toInt(), 0xFF080A0D.toInt())).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), 0x1AFFFFFF)
        }

    private fun flat(radius: Int, fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    /** Thin line that fades in from both sides, like the design's header and dock borders. */
    private fun hline(): View = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x0000D4FF, 0x8000D4FF.toInt(), 0x0000D4FF))
    }

    /** Small press feedback (scale down) that still lets the click through. */
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    private fun iconBtn(res: Int, size: Int, iconSize: Int, bg: Drawable, tint: Int, desc: String, click: () -> Unit) =
        ImageView(context).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dp((size - iconSize) / 2)
            setPadding(p, p, p, p)
            background = bg
            contentDescription = desc
            setOnClickListener { click() }
            pressable(this)
        }

    private fun chip(): TextView = TextView(context).apply {
        textSize = 12f
        typeface = fSemi
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        pressable(this)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF000000.toInt())
        isClickable = true

        val stage = FrameLayout(context)
        wmView = ImageView(context).apply {
            setImageResource(R.drawable.pg_wm)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = wmLevels[uiPrefs.getInt("wm", 2).coerceIn(0, 3)]
        }
        stage.addView(wmView, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        stage.addView(col, FrameLayout.LayoutParams(-1, -1))
        addView(stage, LayoutParams(-1, -1))

        // ---- header: logo, PRAGON (silver), logo opacity, clear log, mute, settings ----
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.pg_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val title = TextView(context).apply {
            text = "PRAGON"
            typeface = fSemi
            letterSpacing = 0.18f
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(13, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setTextColor(silver)
        }
        statusLine = TextView(context).apply {
            textSize = 10f
            typeface = fRegular
            letterSpacing = 0.04f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0x997FD3E8.toInt())
            text = "Standalone mode"
        }
        val titleCol = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            addView(title, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
            addView(statusLine, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        }

        // logo opacity button: half-filled circle + "40%"
        wmLabel = TextView(context).apply {
            textSize = 12.5f
            typeface = fSemi
            setTextColor(iconGrey)
            text = wmNames[uiPrefs.getInt("wm", 2).coerceIn(0, 3)]
        }
        val wmIcon = ImageView(context).apply {
            setImageResource(R.drawable.pg_opacity)
            setColorFilter(iconGrey)
        }
        val wmBtn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            background = ash(12)
            contentDescription = "Change logo opacity"
            addView(wmIcon, LayoutParams(dp(18), dp(18)))
            addView(wmLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(6) })
            setOnClickListener {
                val next = (uiPrefs.getInt("wm", 2) + 1) % 4
                uiPrefs.edit().putInt("wm", next).apply()
                wmView.animate().alpha(wmLevels[next]).setDuration(500).start()
                wmLabel.text = wmNames[next]
            }
            pressable(this)
        }

        clearBtn = iconBtn(R.drawable.pg_trash, 44, 20, ash(12), iconGrey, "Clear log (tap twice to confirm)") { onClearTap() }

        val speaker = iconBtn(
            if (Prefs.muted(context)) R.drawable.pg_mute else R.drawable.pg_unmute,
            44, 20, ash(12), iconGrey, "Mute or unmute spoken replies"
        ) { }
        speaker.setOnClickListener {
            val m = !Prefs.muted(context)
            Prefs.setMuted(context, m)
            speaker.setImageResource(if (m) R.drawable.pg_mute else R.drawable.pg_unmute)
            if (m) Speaker.get(context).stop()
        }
        val gear = iconBtn(R.drawable.pg_gear, 44, 20, ash(12), iconGrey, "Settings") { onSettings() }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setBackgroundColor(0xE0000000.toInt())
            setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(logo, LayoutParams(dp(36), dp(36)).also { it.rightMargin = dp(2) })
            addView(titleCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(wmBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)).also { it.leftMargin = dp(6) })
            addView(clearBtn, LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(6) })
            addView(speaker, LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(6) })
            addView(gear, LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(6) })
        }
        col.addView(header, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        col.addView(hline(), LayoutParams(-1, dp(1)))

        // ---- messages ----
        list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        col.addView(scroll, LayoutParams(-1, 0, 1f))

        // ---- dock row 1: [mode chip][engine chip][float][attach] ----
        modeChip = chip().apply { setOnClickListener { cycleMode() } }
        engineChip = chip().apply {
            setTextColor(cyan)
            background = flat(12, 0x1400D4FF, 0x4D00D4FF)
            setOnClickListener { cycleEngine() }
        }
        floatBtn = iconBtn(R.drawable.pg_float, 44, 22, ash(12), iconGrey, "Float mode: hovering voice bubble") { onFloat() }
        val attachBtn = iconBtn(R.drawable.pg_attach, 44, 22, ash(12), iconGrey, "Attach a file") { onAttach() }
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modeChip, LayoutParams(0, dp(44), 1f))
            addView(engineChip, LayoutParams(0, dp(44), 1.3f).also { it.leftMargin = dp(8) })
            addView(floatBtn, LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(8) })
            addView(attachBtn, LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(8) })
        }
        refreshEngine()
        refreshMode()
        refreshFloat()

        // ---- dock row 2: [input][mic][send] ----
        chips = TextView(context).apply {
            textSize = 12f
            typeface = fMedium
            setTextColor(cyan)
            setPadding(dp(4), dp(2), dp(4), 0)
            visibility = View.GONE
            setOnClickListener { pending.clear(); refreshChips() }
        }
        input = EditText(context).apply {
            hint = "At your service sir..."
            setHintTextColor(0xFF8190A3.toInt())
            setTextColor(0xFFE9EDF4.toInt())
            textSize = 16f
            typeface = fRegular
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = grad(14, 0xFF040506.toInt(), 0xFF0B0E12.toInt(), 0x4D00D4FF)
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit(text.toString(), false)
                    true
                } else false
            }
        }
        val micBtn = iconBtn(
            R.drawable.pg_mic, 52, 24,
            grad(14, 0xFF0C2531.toInt(), 0xFF050B10.toInt(), 0x6600D4FF), 0xFF9BEBFF.toInt(), "Talk to Pragon"
        ) { onMic() }
        val sendBtn = iconBtn(
            R.drawable.pg_send, 52, 22,
            grad(14, 0xFF0B4660.toInt(), 0xFF06222F.toInt(), 0x5700D4FF), 0xFFBFF3FF.toInt(), "Send"
        ) { submit(input.text.toString(), false) }
        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LayoutParams(0, dp(52), 1f))
            addView(micBtn, LayoutParams(dp(52), dp(52)).also { it.leftMargin = dp(8) })
            addView(sendBtn, LayoutParams(dp(52), dp(52)).also { it.leftMargin = dp(8) })
        }

        val dock = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(14))
            addView(row1, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
            addView(chips, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
            addView(row2, LayoutParams(-1, LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })
        }
        col.addView(hline(), LayoutParams(-1, dp(1)))
        col.addView(dock, LayoutParams(-1, LayoutParams.WRAP_CONTENT))

        addBubble(
            "Hi, I'm Pragon. I work right here on your phone. Talk to me, or tell me to open an app, " +
                "search YouTube or change the volume. The left chip above the message box sets my mode: " +
                "ASSISTANT only talks, MASTER talks and acts and tells you what it did, AGENT just acts. " +
                "Tap the ring button next to the chips for float mode, a hovering bubble that keeps listening " +
                "while you use other apps.",
            false
        )
    }

    // ---- engine chip: tap to switch Pragon (online) <-> Ghost <-> Custom ----
    private fun engineText(): String {
        return when (Prefs.engine(context)) {
            "ollama" -> "GHOST · " + Prefs.ollamaModel(context)
            "openai" -> "CUSTOM" + Prefs.openaiModel(context).let { if (it.isBlank()) "" else " · $it" }
            else -> "PRAGON · ONLINE"
        }
    }

    fun refreshEngine() {
        engineChip.text = engineText()
    }

    // ---- mode chip: tap to cycle Assistant -> Master -> Agent ----
    private fun modeColor(m: PMode) = when (m) {
        PMode.ASSISTANT -> 0xFFC4A6FF.toInt()
        PMode.MASTER -> cyan
        PMode.AGENT -> 0xFF6EE7B7.toInt()
    }

    fun refreshMode() {
        val m = Prefs.mode(context)
        val c = modeColor(m)
        modeChip.text = m.label
        modeChip.setTextColor(c)
        modeChip.background = flat(12, (c and 0x00FFFFFF) or 0x1A000000, (c and 0x00FFFFFF) or 0x66000000)
    }

    private fun cycleMode() {
        if (busy) return
        val all = PMode.values()
        val next = all[(Prefs.mode(context).ordinal + 1) % all.size]
        Prefs.saveMode(context, next)
        refreshMode()
        addNote(
            when (next) {
                PMode.ASSISTANT -> "Assistant mode: I only talk with you."
                PMode.MASTER -> "Master mode: I talk and act, and tell you what I did."
                PMode.AGENT -> "Agent mode: I act, with as little talk as possible."
            }
        )
    }

    private fun cycleEngine() {
        if (busy) return
        val order = listOf("gemini", "ollama", "openai")
        val next = order[(order.indexOf(Prefs.engine(context)) + 1) % order.size]
        Prefs.saveEngine(
            context, next, Prefs.ollamaHost(context), Prefs.ollamaModel(context),
            Prefs.openaiBase(context), null, Prefs.openaiModel(context)
        )
        refreshEngine()
        addNote(
            when (next) {
                "ollama" -> "Engine: Ghost (offline / your network) - model ${Prefs.ollamaModel(context)}"
                "openai" -> "Engine: Custom OpenAI-compatible server"
                else -> "Engine: Pragon (online)"
            }
        )
    }

    // keep the chips right after coming back from Settings
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) { refreshEngine(); refreshMode(); refreshFloat() }
    }

    /** Lights the float button up (and makes it breathe) while float mode is running. */
    fun refreshFloat() {
        val on = FloatService.running
        floatBtn.setColorFilter(if (on) cyan else iconGrey)
        floatBtn.background = if (on) flat(12, 0x2400D4FF, 0x9900D4FF.toInt()) else ash(12)
        if (on && isAttachedToWindow && visibility == VISIBLE) startBreath() else stopBreath()
    }

    private fun startBreath() {
        if (floatBreath == null) {
            floatBreath = ObjectAnimator.ofFloat(floatBtn, View.ALPHA, 1f, 0.6f).apply {
                duration = 1300
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
        }
        if (floatBreath?.isStarted != true) floatBreath?.start()
    }

    private fun stopBreath() {
        floatBreath?.cancel()
        floatBtn.alpha = 1f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshFloat() }
    override fun onDetachedFromWindow() { stopBreath(); typingAnims.forEach { it.cancel() }; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this || !isAttachedToWindow) return
        if (visibility == VISIBLE) refreshFloat() else stopBreath()
    }

    // ---- clear log: tap once (turns red), tap again within 2.5 s to clear ----
    private fun onClearTap() {
        if (confirmClear) {
            confirmClear = false
            hideTyping()
            list.removeAllViews()
            styleClear(false)
            return
        }
        confirmClear = true
        styleClear(true)
        postDelayed({ if (confirmClear) { confirmClear = false; styleClear(false) } }, 2500)
    }

    private fun styleClear(danger: Boolean) {
        clearBtn.setColorFilter(if (danger) 0xFFFF9A94.toInt() else iconGrey)
        clearBtn.background = if (danger) flat(12, 0x29FF5A5A, 0xA6FF5A5A.toInt()) else ash(12)
    }

    // ---- messages ----
    private fun appear(v: View) {
        v.alpha = 0f
        v.translationY = dp(10).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(400).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun addBubble(text: String, mine: Boolean): TextView {
        val r = dp(16).toFloat()
        val small = dp(4).toFloat()
        val tv = TextView(context).apply {
            this.text = text
            textSize = 16f
            typeface = fRegular
            setLineSpacing(0f, 1.2f)
            setTextColor(if (mine) 0xFFE9EDF4.toInt() else 0xFFD2F3FF.toInt())
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = (if (mine)
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0D0F13.toInt(), 0xFF050607.toInt()))
            else GradientDrawable().apply { setColor(0x1700D4FF) }).apply {
                setStroke(dp(1), if (mine) 0x1FFFFFFF else 0x3300D4FF)
                // order: TL, TR, BR, BL (x,y pairs)
                cornerRadii = if (mine) floatArrayOf(r, r, r, r, small, small, r, r)
                else floatArrayOf(r, r, r, r, r, r, small, small)
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.84).toInt()
            setTextIsSelectable(true)
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.setMargins(0, dp(5), 0, dp(5))
        list.addView(tv, lp)
        appear(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun addNote(text: String) {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 13.5f
            typeface = fItalic
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setTextColor(0xFF7FD3E8.toInt())
        }
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(5), 0, dp(5))
        list.addView(tv, lp)
        appear(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Three pulsing dots in an assistant bubble while a reply is coming. */
    private fun showTyping() {
        hideTyping()
        val r = dp(16).toFloat()
        val small = dp(4).toFloat()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            contentDescription = "Pragon is typing"
            background = GradientDrawable().apply {
                setColor(0x1700D4FF)
                setStroke(dp(1), 0x3300D4FF)
                cornerRadii = floatArrayOf(r, r, r, r, r, r, small, small)
            }
        }
        for (i in 0 until 3) {
            val dot = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cyan) }
                alpha = 0.25f
            }
            row.addView(dot, LayoutParams(dp(6), dp(6)).also { if (i > 0) it.leftMargin = dp(5) })
            val a = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f, 0.25f).apply {
                duration = 1100
                startDelay = i * 150L
                repeatCount = ObjectAnimator.INFINITE
            }
            val b = ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(3).toFloat(), 0f).apply {
                duration = 1100
                startDelay = i * 150L
                repeatCount = ObjectAnimator.INFINITE
            }
            typingAnims.add(a); typingAnims.add(b)
            a.start(); b.start()
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.START
        lp.setMargins(0, dp(5), 0, dp(5))
        list.addView(row, lp)
        typingRow = row
        appear(row)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideTyping() {
        typingAnims.forEach { it.cancel() }
        typingAnims.clear()
        typingRow?.let { list.removeView(it) }
        typingRow = null
    }

    // ---- attachments ----
    private fun refreshChips() {
        if (pending.isEmpty()) { chips.visibility = View.GONE; return }
        chips.text = "📎 " + pending.joinToString(", ") { it.name } + "   (tap to remove)"
        chips.visibility = View.VISIBLE
    }

    /** Called by MainActivity after the person picks a file with the paperclip. */
    fun addAttachment(name: String, mime: String, bytes: ByteArray) {
        val ok = mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime.startsWith("text/") || mime == "application/pdf"
        if (!ok) { addNote("Pragon can read images, PDFs, audio, video and text files. $name isn't one of those."); return }
        val used = pending.sumOf { it.b64.length }
        if (bytes.size > 12 * 1024 * 1024 || used + bytes.size * 4 / 3 > 16 * 1024 * 1024) {
            addNote("$name is too big to send. Try a smaller file."); return
        }
        pending.add(Attachment(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP)))
        refreshChips()
    }

    /** Send a message (typed or spoken). Spoken messages get spoken replies. */
    fun submit(text: String, viaVoice: Boolean) {
        val t = text.trim()
        if ((t.isEmpty() && pending.isEmpty()) || busy) return
        busy = true
        input.setText("")
        val files = pending.toList()
        pending.clear()
        refreshChips()
        val prompt = if (t.isEmpty()) "Please look at the attached file." else t
        val shown = if (files.isEmpty()) t else
            (if (t.isEmpty()) "" else t + "\n") + files.joinToString("\n") { "📎 " + it.name }
        addBubble(shown, true)
        showTyping()
        var reply: TextView? = null      // the assistant's bubble, created when the first words arrive
        val sp = Speaker.get(context)
        val speakIt = !Prefs.muted(context) && (viaVoice || Prefs.speakTyped(context))
        if (speakIt) sp.stop()
        worker.execute {
            val sink = object : Brain.Sink {
                override fun onPartial(textSoFar: String) {
                    post {
                        val b = reply ?: run { hideTyping(); addBubble(textSoFar, false).also { reply = it } }
                        b.text = textSoFar
                        scroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
                override fun onSentence(sentence: String) { if (speakIt) sp.enqueue(sentence) }
            }
            val result = try {
                Brain.respond(context, prompt, files, viaVoice, sink)
            } catch (e: Exception) {
                Brain.Reply("Something went wrong: ${e.message}", true, false)
            }
            post {
                hideTyping()
                val b = reply ?: addBubble(result.text, false).also { reply = it }
                b.text = result.text
                busy = false
                refreshMode()
                scroll.fullScroll(View.FOCUS_DOWN)
                if (speakIt && result.speak && !result.streamed) sp.speak(result.text)
            }
        }
    }
}
