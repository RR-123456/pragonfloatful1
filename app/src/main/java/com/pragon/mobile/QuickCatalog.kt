package com.pragon.mobile

/**
 * v13: every phone switch Pragon knows by name. Pure data and phrase matching (no Android classes), so it can be
 * tested on a plain JVM. Quick.kt does the real work on the phone.
 *
 * kind: sw = on/off switch, btn = one-shot button, lvl = a percentage, ch = one of a fixed list of choices.
 * cat:  access, display, sound, notif, tiles (the panel groups).
 * tile: the words the phone's own Quick Settings tile may show (used when Android gives no direct API).
 */
object QuickCatalog {

    class Item(
        val id: String, val label: String, val cat: String, val sec: String, val kind: String,
        val words: List<String>, val tile: List<String> = emptyList(), val options: List<String> = emptyList(),
    )

    const val RING_MODES = "ring,vibrate,silent"
    val SIZES = listOf("small", "default", "large", "largest")
    val TIMEOUTS = listOf("15s", "30s", "1m", "2m", "5m", "10m")
    val RING = listOf("ring", "vibrate", "silent")

    val ALL: List<Item> = listOf(
        // ---- accessibility and convenience ----
        Item("talkback", "TalkBack screen reader", "access", "Accessibility", "sw", listOf("talkback", "talk back", "screen reader")),
        Item("selecttospeak", "Select to Speak", "access", "Accessibility", "sw", listOf("select to speak"), listOf("Select to Speak")),
        Item("caption", "Live Caption", "access", "Accessibility", "sw", listOf("live caption", "live captions", "captions", "caption")),
        Item("colorinvert", "Colour inversion", "access", "Accessibility", "sw", listOf("color inversion", "colour inversion", "invert colors", "invert colours", "invert color")),
        Item("hightext", "High contrast text", "access", "Accessibility", "sw", listOf("high contrast text", "high contrast")),
        Item("noanim", "Remove animations", "access", "Accessibility", "sw", listOf("remove animations", "animations", "animation")),
        Item("monoaudio", "Mono audio", "access", "Accessibility", "sw", listOf("mono audio", "mono sound")),
        Item("fontsize", "Text size", "access", "Accessibility", "ch", listOf("text size", "font size"), options = SIZES),
        Item("rotate", "Auto-rotate", "access", "Convenience", "sw", listOf("auto rotate", "auto-rotate", "autorotate", "screen rotation", "rotation", "rotate"), listOf("Auto-rotate", "Auto rotate", "Portrait", "Landscape")),
        Item("haptic", "Vibrate on touch", "access", "Convenience", "sw", listOf("vibrate on touch", "touch vibration", "haptic feedback", "haptics", "haptic")),
        Item("workapps", "Work apps", "access", "Convenience", "sw", listOf("work apps", "work profile", "work mode"), listOf("Work apps", "Work profile")),
        Item("focus", "Focus", "access", "Convenience", "sw", listOf("focus mode", "focus"), listOf("Focus")),
        Item("zenspace", "Zen Space", "access", "Convenience", "sw", listOf("zen space", "zen mode"), listOf("Zen Space")),
        Item("kids", "Kids mode", "access", "Convenience", "sw", listOf("kids mode", "kid mode", "kids space"), listOf("Kids mode", "Kids")),

        // ---- display and brightness ----
        Item("brightness", "Brightness", "display", "Brightness", "lvl", listOf("brightness", "screen brightness")),
        Item("autobright", "Adaptive brightness", "display", "Brightness", "sw", listOf("adaptive brightness", "auto brightness", "automatic brightness")),
        Item("timeout", "Screen timeout", "display", "Display", "ch", listOf("screen timeout", "screen off time", "sleep time", "timeout"), options = TIMEOUTS),
        Item("darkmode", "Dark mode", "display", "Display", "sw", listOf("dark mode", "dark theme", "night mode", "dark"), listOf("Dark mode", "Dark theme", "Dark")),
        Item("eyecomfort", "Eye Comfort", "display", "Display", "sw", listOf("eye comfort", "night light", "blue light filter", "reading mode"), listOf("Eye Comfort", "Night Light", "Reading mode")),
        Item("extradim", "Extra dim", "display", "Display", "sw", listOf("extra dim"), listOf("Extra dim")),
        Item("highperf", "High performance", "display", "Display", "sw", listOf("high performance mode", "high performance", "performance mode"), listOf("High performance", "Performance mode")),
        Item("powersave", "Power saving", "display", "Display", "sw", listOf("power saving mode", "power saving", "power saver", "battery saver", "battery saving"), listOf("Power saving", "Battery Saver", "Battery saver")),

        // ---- sound and vibration ----
        Item("mediavol", "Media volume", "sound", "Volume", "lvl", listOf("media volume", "music volume", "volume")),
        Item("ringvol", "Ring volume", "sound", "Volume", "lvl", listOf("ring volume", "ringtone volume", "ringer volume")),
        Item("alarmvol", "Alarm volume", "sound", "Volume", "lvl", listOf("alarm volume")),
        Item("ringmode", "Ring mode", "sound", "Ring and vibration", "ch", listOf("ring mode", "sound mode", "ringer mode"), listOf("Ring", "Vibrate", "Silent", "Mute"), RING),
        Item("dnd", "Do Not Disturb", "sound", "Ring and vibration", "sw", listOf("do not disturb", "don't disturb", "dnd", "dont disturb"), listOf("Do Not Disturb")),
        Item("touchsounds", "Touch sounds", "sound", "Ring and vibration", "sw", listOf("touch sounds", "touch sound", "tap sounds", "click sounds")),
        Item("lockSounds", "Screen lock sound", "sound", "Ring and vibration", "sw", listOf("screen lock sound", "lock sound", "lock sounds")),
        Item("clearvoice", "Clear Voice", "sound", "Ring and vibration", "sw", listOf("clear voice"), listOf("Clear Voice")),

        // ---- notifications ----
        Item("clearall", "Clear all notifications", "notif", "Notifications", "btn", listOf("clear all notifications", "clear notifications", "dismiss all notifications", "clear all")),
        Item("lockNotif", "Notifications on lock screen", "notif", "Notifications", "sw", listOf("lock screen notifications", "notifications on lock screen", "lockscreen notifications")),
        Item("notifsettings", "Notification settings", "notif", "Notifications", "btn", listOf("notification settings", "app notifications")),

        // ---- the tiles of the Quick Settings panel ----
        Item("wifi", "Wi-Fi", "tiles", "Connections", "sw", listOf("wifi", "wi-fi", "wi fi", "wireless"), listOf("Wi-Fi", "WiFi", "Internet")),
        Item("bluetooth", "Bluetooth", "tiles", "Connections", "sw", listOf("bluetooth", "blue tooth"), listOf("Bluetooth")),
        Item("mobiledata", "Mobile data", "tiles", "Connections", "sw", listOf("mobile data", "cellular data", "sim 1", "sim1", "sim one", "data", "sim"), listOf("SIM1", "SIM 1", "SIM", "Mobile data", "Data")),
        Item("airplane", "Aeroplane mode", "tiles", "Connections", "sw", listOf("airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane"), listOf("Aeroplane mode", "Airplane mode", "Flight mode")),
        Item("hotspot", "Personal hotspot", "tiles", "Connections", "sw", listOf("personal hotspot", "mobile hotspot", "wifi hotspot", "hotspot", "tethering"), listOf("Personal hotspot", "Hotspot")),
        Item("location", "Location", "tiles", "Connections", "sw", listOf("location", "gps"), listOf("Location")),
        Item("nfc", "NFC", "tiles", "Connections", "sw", listOf("nfc"), listOf("NFC")),
        Item("quickshare", "Quick Share", "tiles", "Connections", "btn", listOf("quick share"), listOf("Quick Share")),
        Item("sharepc", "Share with iPhone or PC", "tiles", "Connections", "btn", listOf("share with iphone or pc", "share with pc", "share with iphone"), listOf("Share with iPhone", "Share with")),
        Item("linkwin", "Link to Windows", "tiles", "Connections", "btn", listOf("link to windows", "phone link"), listOf("Link to Windows")),
        Item("torch", "Torch", "tiles", "Tools", "sw", listOf("flashlight", "torch", "flash light"), listOf("Torch", "Flashlight")),
        Item("screenrec", "Screen recording", "tiles", "Tools", "btn", listOf("screen recording", "screen recorder", "record the screen", "record screen"), listOf("Screen recording", "Screen record")),
        Item("screenshot", "Screenshot", "tiles", "Tools", "btn", listOf("screenshot", "screen shot"), listOf("Screenshot")),
        Item("screencast", "Screencast", "tiles", "Tools", "btn", listOf("screencast", "screen cast", "cast screen"), listOf("Screencast", "Cast")),
        Item("qrcode", "QR code", "tiles", "Tools", "btn", listOf("qr code", "qr scanner", "scan qr code", "qr"), listOf("QR code", "QR")),
        Item("calculator", "Calculator", "tiles", "Tools", "btn", listOf("calculator tile"), listOf("Calculator")),
        Item("songsearch", "Song search", "tiles", "Tools", "btn", listOf("song search", "shazam", "identify song"), listOf("Song search")),
        Item("voicescribe", "AI VoiceScribe", "tiles", "Tools", "btn", listOf("ai voicescribe", "voice scribe", "voicescribe"), listOf("AI VoiceScribe", "VoiceScribe")),
        Item("opencamera", "Open Camera", "tiles", "Tools", "btn", listOf("open camera tile", "camera tile"), listOf("Open Camera")),
        Item("devicecontrols", "Device controls", "tiles", "Tools", "btn", listOf("device controls", "home controls", "smart home"), listOf("Device controls")),
        Item("wallet", "E-wallet", "tiles", "Tools", "btn", listOf("e wallet", "ewallet", "wallet"), listOf("E-wallet", "Wallet")),
        Item("copilot", "Talk to Copilot", "tiles", "Tools", "btn", listOf("talk to copilot", "copilot"), listOf("Talk to Copilot", "Copilot")),
        Item("musicolet", "Musicolet", "tiles", "Tools", "btn", listOf("musicolet"), listOf("Musicolet")),
        Item("assistant", "Digital assistant", "tiles", "Tools", "btn", listOf("digital assistant", "voice assistant"), listOf("Digital assistant")),
        Item("changekeyboard", "Change keyboard", "tiles", "Tools", "btn", listOf("change keyboard", "switch keyboard"), listOf("Change keyboard")),
        Item("speakerclean", "Speaker cleaner", "tiles", "Tools", "btn", listOf("speaker cleaner", "clean speaker"), listOf("Speaker cleaner")),
        Item("storage", "Storage", "tiles", "Tools", "btn", listOf("storage tile"), listOf("Storage")),
        Item("cleanup", "Cleanup", "tiles", "Tools", "btn", listOf("cleanup", "clean up phone"), listOf("Cleanup")),
        Item("security", "Security and privacy", "tiles", "Tools", "btn", listOf("security and privacy", "security check", "security tile"), listOf("Security and p", "Security")),
        Item("micaccess", "Microphone access", "tiles", "Privacy", "sw", listOf("microphone access", "mic access", "microphone permission"), listOf("Microphone access", "Mic access")),
        Item("camaccess", "Camera access", "tiles", "Privacy", "sw", listOf("camera access", "camera permission"), listOf("Camera access")),
    )

    /** These two are privacy switches: Pragon only flips them when the person asks directly, never from an AI tool call. */
    val PRIVATE = setOf("micaccess", "camaccess")

    fun byId(id: String): Item? = ALL.firstOrNull { it.id == id }

    private fun norm(s: String) = s.lowercase().replace("\u2019", "'").replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** The item a spoken name means: longest matching name wins ("high contrast text" before "text"). */
    fun find(name: String): Item? {
        val n = norm(name).removePrefix("the ").removePrefix("my ").removeSuffix(" tile").removeSuffix(" toggle").trim()
        if (n.isEmpty()) return null
        byId(n.replace(" ", ""))?.let { return it }
        var best: Item? = null
        var bestLen = 0
        for (it in ALL) {
            for (w in it.words + it.label) {
                val nw = norm(w)
                if (nw == n && nw.length + 100 > bestLen) { best = it; bestLen = nw.length + 100 }
            }
        }
        if (best != null) return best
        for (it in ALL) for (w in it.words) {
            val nw = norm(w)
            if (nw.length >= 3 && n.contains(nw) && nw.length > bestLen) { best = it; bestLen = nw.length }
        }
        return best
    }

    /** Names to put in one big pattern, longest first so the regex prefers the long name. */
    fun nameAlternation(): String =
        ALL.flatMap { it.words }.map { norm(it) }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
            .joinToString("|") { it.replace(" ", "\\s+") }
}
