package com.pragon.mobile

/**
 * v13 phrases (no model, no AI key, no Android classes): the music player and every phone switch in [QuickCatalog].
 * Returns action "music" (value like play, pause, next, shuffle:on, play:<words>) or "quick" (value like wifi:on, brightness:60).
 */
object QuickVoice {

    private val I = RegexOption.IGNORE_CASE
    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|please|yo|can you|could you|would you)\\s+)+", I)
    private val LEAD_P = Regex("^(?:pragon|paragon|pragan|pragun)\\b[,]?\\s*", I)
    private val TRAIL = Regex("(?:\\s+(?:please|now|for me))+$", I)

    private fun tidy(raw: String): String {
        var s = raw.trim().replace("\u2019", "'").replace(Regex("\\s+"), " ")
        s = LEAD.replaceFirst(s, "")
        s = LEAD_P.replaceFirst(s, "")
        s = LEAD.replaceFirst(s, "")
        s = TRAIL.replace(s, "")
        return s.trim().trimEnd('.', '!', '?', ',').trim()
    }

    private fun a(action: String, value: String) = VoiceParser.Action(action, value)

    // ───────────── music ─────────────
    private const val M = "(?:music|songs?|tracks?|player|playback|playing)"
    private val MU_POWER = Regex("^(?:turn|switch|set)\\s+(on|off)\\s+(?:the\\s+|my\\s+)?music\\s+player$|^(enable|disable)\\s+(?:the\\s+|my\\s+)?music\\s+player$", I)
    private val MU_STOP = Regex("^stop\\s+(?:the\\s+|my\\s+)?$M$", I)
    private val MU_PAUSE = Regex("^pause\\s+(?:the\\s+|my\\s+|this\\s+)?$M$", I)
    private val MU_PLAY = Regex("^(?:play|resume|start|continue)\\s+(?:some\\s+|the\\s+|my\\s+)?(?:music|songs|playlist|tunes)(?:\\s+(?:on|from)\\s+(?:my\\s+|the\\s+)?(?:phone|device))?$|^resume\\s+(?:the\\s+)?(?:song|track)$", I)
    private val MU_NEXT = Regex("^(?:play\\s+)?(?:the\\s+)?next\\s+(?:song|track)$|^skip(?:\\s+(?:this|the))?\\s+(?:song|track)$|^(?:next|skip)\\s+(?:song|track|music)$", I)
    private val MU_PREV = Regex("^(?:play\\s+)?(?:the\\s+)?(?:previous|last)\\s+(?:song|track)$|^go\\s+back\\s+(?:a|one)\\s+(?:song|track)$", I)
    private val MU_SHUF = Regex("^(?:turn\\s+)?(on|off)\\s+(?:the\\s+)?shuffle$|^shuffle\\s+(on|off)$|^(?:turn\\s+)?shuffle\\s+(on|off)$", I)
    private val MU_SHUF_PLAY = Regex("^shuffle\\s+(?:the\\s+|my\\s+)?(?:music|songs|playlist)$|^play\\s+(?:music|songs)\\s+(?:on\\s+)?shuffle$", I)
    private val MU_REP = Regex("^repeat\\s+(one|all|off|on|this\\s+song|this\\s+track)$|^(?:turn\\s+)?(on|off)\\s+repeat$", I)
    private val MU_STATUS = Regex("^(?:what(?:'s|\\s+is)\\s+playing|what\\s+song\\s+is\\s+(?:this|playing)|which\\s+song\\s+is\\s+(?:this|playing)|(?:current|now\\s+playing)\\s+(?:song|track)|now\\s+playing)$", I)
    private val MU_Q1 = Regex("^play\\s+(.+?)\\s+(?:from|on)\\s+(?:my\\s+|the\\s+)?(?:phone|device|music\\s+player|local\\s+music)$", I)
    private val MU_Q2 = Regex("^play\\s+(?:the\\s+)?(?:song|track|album|artist)\\s+(.+)$", I)

    // ───────────── phone switches ─────────────
    private val N = QuickCatalog.nameAlternation()
    private const val SUFFIX = "(?:\\s+(?:mode|tile|setting|settings|toggle|button))?"
    private val T_ONOFF = Regex("^(?:turn|switch|set|put)\\s+(on|off)\\s+(?:the\\s+|my\\s+)?($N)$SUFFIX$", I)
    private val T_ONOFF2 = Regex("^(?:turn|switch|set|put)\\s+(?:the\\s+|my\\s+)?($N)$SUFFIX\\s+(on|off)$", I)
    private val T_EN = Regex("^(enable|disable|activate|deactivate)\\s+(?:the\\s+|my\\s+)?($N)$SUFFIX$", I)
    private val T_TOGGLE = Regex("^(?:toggle|flip|switch)\\s+(?:the\\s+|my\\s+)?($N)$SUFFIX$", I)
    private val T_PRESS = Regex("^(?:press|tap|click|hit|open|run|start|use)\\s+(?:the\\s+)?($N)\\s+(?:tile|button|toggle)$", I)
    private val T_NAME_ONOFF = Regex("^($N)$SUFFIX\\s+(on|off)$", I)

    private val LEVEL = Regex("^(?:set|change|put|make|turn)\\s+(?:the\\s+|my\\s+)?(brightness|screen\\s+brightness|media\\s+volume|music\\s+volume|ring\\s+volume|ringtone\\s+volume|ringer\\s+volume|alarm\\s+volume|volume)\\s+(?:to|at)\\s+(\\d{1,3})(?:\\s*(?:%|percent))?$|^(brightness|screen\\s+brightness|media\\s+volume|ring\\s+volume|alarm\\s+volume)\\s+(?:to\\s+|at\\s+)?(\\d{1,3})(?:\\s*(?:%|percent))$", I)

    private val RING1 = Regex("^(?:set|change|switch|put)\\s+(?:the\\s+|my\\s+)?(?:ring(?:er)?|sound)\\s+mode\\s+(?:to|as)\\s+(ring|ringing|normal|vibrate|vibration|silent|mute|muted)$", I)
    private val RING2 = Regex("^(?:put|set|switch|turn)\\s+(?:my\\s+|the\\s+)?(?:phone|mobile|device)\\s+(?:on|to|in)\\s+(ring|normal|vibrate|vibration|silent|mute)(?:\\s+mode)?$", I)
    private val RING3 = Regex("^(silent|vibrate|vibration)\\s+mode\\s+(on|off)$|^(?:turn|switch)\\s+(on|off)\\s+(silent|vibrate|vibration)\\s+mode$|^(?:go|switch)\\s+(?:to\\s+)?(silent|vibrate|normal)(?:\\s+mode)?$|^(?:turn\\s+)?(?:off|disable)\\s+silent$", I)

    private val FONT1 = Regex("^(?:set|change|put|make)\\s+(?:the\\s+|my\\s+)?(?:text|font)\\s+size\\s+(?:to\\s+)?(small|smaller|default|normal|large|larger|big|bigger|largest|huge)$", I)
    private val FONT2 = Regex("^make\\s+(?:the\\s+)?(?:text|font|writing)\\s+(bigger|larger|smaller)$", I)
    private val TIMEOUT = Regex("^(?:set|change|put)\\s+(?:the\\s+|my\\s+)?(?:screen\\s+)?(?:timeout|screen\\s+off\\s+time|sleep\\s+time)\\s+(?:to|at)\\s+(\\d+|fifteen|thirty|one|two|five|ten)\\s*(seconds?|secs?|s|minutes?|mins?|m)$", I)
    private val CLEAR = Regex("^(?:clear|dismiss|remove|delete)\\s+(?:all\\s+)?(?:my\\s+|the\\s+)?notifications$|^(?:clear|dismiss)\\s+all$|^clear\\s+all\\s+notifications$", I)

    private val ANIM = Regex("^(?:remove|disable|stop)\\s+(?:all\\s+|the\\s+)?animations$", I)

    private fun id(name: String) = QuickCatalog.find(name)?.id

    private fun toggle(name: String, onOff: String): VoiceParser.Action? {
        val i = QuickCatalog.find(name) ?: return null
        var st = when (onOff.lowercase()) {
            "on", "enable", "activate" -> "on"
            "off", "disable", "deactivate" -> "off"
            else -> "toggle"
        }
        // "turn off animations" means the same as "remove animations": the switch in Quick is "remove animations"
        if (i.id == "noanim" && !name.contains("remove", true)) st = when (st) { "on" -> "off"; "off" -> "on"; else -> st }
        if (i.kind == "btn") return a("quick", "${i.id}:run")
        if (i.kind == "lvl") return if (st == "off") a("quick", "${i.id}:min") else if (st == "on") a("quick", "${i.id}:max") else null
        if (i.kind == "ch") return when (i.id) {
            "ringmode" -> a("quick", "ringmode:" + if (st == "off") "ring" else "silent")
            else -> null
        }
        return a("quick", "${i.id}:$st")
    }

    private fun word(n: String): Int = when (n.lowercase()) {
        "fifteen" -> 15; "thirty" -> 30; "one" -> 1; "two" -> 2; "five" -> 5; "ten" -> 10; else -> n.toIntOrNull() ?: 0
    }

    fun parse(raw: String): VoiceParser.Action? {
        val t = tidy(raw).replace(Regex("(?<=\\w)-(?=\\w)"), " ")
        if (t.isEmpty() || t.length > 120) return null
        if (t.contains("youtube", true) || t.contains("spotify", true)) return null

        // ---- music first ----
        MU_POWER.matchEntire(t)?.let { m -> val x = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase(); return a("music", if (x == "on" || x == "enable") "on" else "off") }
        MU_STOP.matchEntire(t)?.let { return a("music", "stop") }
        MU_PAUSE.matchEntire(t)?.let { return a("music", "pause") }
        MU_SHUF_PLAY.matchEntire(t)?.let { return a("music", "shuffle_play") }
        MU_PLAY.matchEntire(t)?.let { return a("music", "play") }
        MU_NEXT.matchEntire(t)?.let { return a("music", "next") }
        MU_PREV.matchEntire(t)?.let { return a("music", "previous") }
        MU_SHUF.matchEntire(t)?.let { m -> return a("music", "shuffle:" + (1..3).map { m.groupValues[it] }.first { it.isNotEmpty() }.lowercase()) }
        MU_REP.matchEntire(t)?.let { m ->
            val x = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            return a("music", "repeat:" + when { x.startsWith("this") -> "one"; x == "on" -> "all"; else -> x })
        }
        MU_STATUS.matchEntire(t)?.let { return a("music", "status") }
        MU_Q1.matchEntire(t)?.let { m -> val q = m.groupValues[1].trim(); if (q.isNotEmpty()) return a("music", "play:$q") }
        MU_Q2.matchEntire(t)?.let { m -> return a("music", "play:" + m.groupValues[1].trim()) }

        // ---- special phrases ----
        ANIM.matchEntire(t)?.let { return a("quick", "noanim:on") }
        CLEAR.matchEntire(t)?.let { return a("quick", "clearall:run") }
        LEVEL.matchEntire(t)?.let { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val num = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return null
            val i = id(name) ?: return null
            return a("quick", "$i:${num.coerceIn(0, 100)}")
        }
        RING1.matchEntire(t)?.let { m -> return a("quick", "ringmode:" + ringWord(m.groupValues[1])) }
        RING2.matchEntire(t)?.let { m -> return a("quick", "ringmode:" + ringWord(m.groupValues[1])) }
        RING3.matchEntire(t)?.let { m ->
            val g = m.groupValues
            return when {
                g[1].isNotEmpty() -> a("quick", "ringmode:" + if (g[2].lowercase() == "on") ringWord(g[1]) else "ring")
                g[4].isNotEmpty() -> a("quick", "ringmode:" + if (g[3].lowercase() == "on") ringWord(g[4]) else "ring")
                g[5].isNotEmpty() -> a("quick", "ringmode:" + ringWord(g[5]))
                else -> a("quick", "ringmode:ring")
            }
        }
        FONT1.matchEntire(t)?.let { m ->
            val s = when (m.groupValues[1].lowercase()) { "small", "smaller" -> "small"; "default", "normal" -> "default"; "large", "larger", "big", "bigger" -> "large"; else -> "largest" }
            return a("quick", "fontsize:$s")
        }
        FONT2.matchEntire(t)?.let { m -> return a("quick", "fontsize:" + if (m.groupValues[1].lowercase() == "smaller") "down" else "up") }
        TIMEOUT.matchEntire(t)?.let { m ->
            val n = word(m.groupValues[1]); val unit = m.groupValues[2].lowercase()
            val secs = if (unit.startsWith("s")) n else n * 60
            val key = if (secs < 60) "${secs}s" else "${secs / 60}m"
            if (key in QuickCatalog.TIMEOUTS) return a("quick", "timeout:$key")
            return null
        }

        // ---- on / off / toggle / press ----
        T_ONOFF.matchEntire(t)?.let { m -> return toggle(m.groupValues[2], m.groupValues[1]) }
        T_ONOFF2.matchEntire(t)?.let { m -> return toggle(m.groupValues[1], m.groupValues[2]) }
        T_EN.matchEntire(t)?.let { m -> return toggle(m.groupValues[2], m.groupValues[1]) }
        T_TOGGLE.matchEntire(t)?.let { m -> return toggle(m.groupValues[1], "toggle") }
        T_NAME_ONOFF.matchEntire(t)?.let { m -> return toggle(m.groupValues[1], m.groupValues[2]) }
        T_PRESS.matchEntire(t)?.let { m ->
            val n = m.groupValues[1]
            val i = QuickCatalog.find(n) ?: return null
            return a("quick", if (i.kind == "sw") "${i.id}:toggle" else "${i.id}:run")
        }
        return null
    }

    private fun ringWord(w: String) = when (w.lowercase()) {
        "ring", "ringing", "normal" -> "ring"
        "vibrate", "vibration" -> "vibrate"
        else -> "silent"
    }
}
