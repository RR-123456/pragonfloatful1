package com.pragon.mobile

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * v13: flips phone settings and Quick Settings tiles by name (voice, AI tool call or the settings panel).
 * Order of attack for every switch: (1) the real Android setting when an app may change it, (2) the tile of the
 * Quick Settings panel pressed through the accessibility service, (3) opening the right Settings screen.
 * Value format: "<id>:<arg>"  arg = on | off | toggle | run | 0..100 | max | min | up | down | a choice | state
 */
object Quick {

    private fun ok(m: String, label: String) = CommandExecutor.Res(true, m, label = label)
    private fun no(m: String, label: String = "") = CommandExecutor.Res(false, m, label = label)
    private fun open(c: Context, i: Intent): Boolean = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    private fun onOff(b: Boolean?) = if (b == null) "?" else if (b) "on" else "off"

    private fun sysInt(c: Context, k: String, d: Int) = try { Settings.System.getInt(c.contentResolver, k, d) } catch (e: Exception) { d }
    private fun secInt(c: Context, k: String, d: Int) = try { Settings.Secure.getInt(c.contentResolver, k, d) } catch (e: Exception) { d }
    private fun globInt(c: Context, k: String, d: Int) = try { Settings.Global.getInt(c.contentResolver, k, d) } catch (e: Exception) { d }

    private fun canWriteSettings(c: Context) = Settings.System.canWrite(c)
    private fun canWriteSecure(c: Context) = AccessFeatures.canWriteSecure(c)

    private fun audio(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun dndAllowed(c: Context) = (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    // ───────────── reading ─────────────

    /** "on" / "off" / "?" for a switch; "0".."100" for a level; the choice for a list; "" for a button. */
    fun stateOf(c: Context, it: QuickCatalog.Item): String = try {
        when (it.id) {
            "wifi" -> onOff((c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled)
            "bluetooth" -> onOff((c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled)
            "airplane" -> onOff(globInt(c, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            "rotate" -> onOff(sysInt(c, Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
            "location" -> onOff((c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled)
            "torch" -> onOff(CommandExecutor.torchOn)
            "dnd" -> onOff((c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            "powersave" -> onOff((c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode)
            "darkmode" -> onOff((c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            "autobright" -> onOff(sysInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            "haptic" -> onOff(sysInt(c, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1)
            "touchsounds" -> onOff(sysInt(c, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1)
            "lockSounds" -> onOff(secInt(c, "lockscreen_sounds_enabled", 1) == 1)
            "monoaudio" -> onOff(sysInt(c, "master_mono", 0) == 1)
            "caption" -> onOff(secInt(c, "odi_captions_enabled", 0) == 1)
            "colorinvert" -> onOff(secInt(c, "accessibility_display_inversion_enabled", 0) == 1)
            "hightext" -> onOff(secInt(c, "high_text_contrast_enabled", 0) == 1)
            "noanim" -> onOff(try { Settings.Global.getFloat(c.contentResolver, "animator_duration_scale", 1f) == 0f } catch (e: Exception) { false })
            "lockNotif" -> onOff(secInt(c, "lock_screen_show_notifications", 1) == 1)
            "talkback" -> onOff((Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("talkback", true))
            "selecttospeak" -> onOff((Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("selecttospeak", true))
            "brightness" -> ((sysInt(c, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 + 127) / 255).toString()
            "mediavol" -> pctOf(c, AudioManager.STREAM_MUSIC)
            "ringvol" -> pctOf(c, AudioManager.STREAM_RING)
            "alarmvol" -> pctOf(c, AudioManager.STREAM_ALARM)
            "ringmode" -> when (audio(c).ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "ring" }
            "fontsize" -> {
                val f = try { Settings.System.getFloat(c.contentResolver, Settings.System.FONT_SCALE, 1f) } catch (e: Exception) { 1f }
                QuickCatalog.SIZES.minByOrNull { n -> Math.abs(fontScale(n) - f) } ?: "default"
            }
            "timeout" -> {
                val ms = sysInt(c, Settings.System.SCREEN_OFF_TIMEOUT, 30000)
                QuickCatalog.TIMEOUTS.minByOrNull { n -> Math.abs(timeoutMs(n) - ms) } ?: "30s"
            }
            else -> if (it.kind == "sw") "?" else ""
        }
    } catch (e: Exception) { if (it.kind == "sw") "?" else "" }

    private fun pctOf(c: Context, stream: Int): String {
        val am = audio(c); val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return ((am.getStreamVolume(stream) * 100 + max / 2) / max).toString()
    }

    private fun fontScale(n: String) = when (n) { "small" -> 0.85f; "large" -> 1.15f; "largest" -> 1.3f; else -> 1.0f }
    private fun timeoutMs(n: String) = when (n) {
        "15s" -> 15000; "30s" -> 30000; "1m" -> 60000; "2m" -> 120000; "5m" -> 300000; "10m" -> 600000; else -> 30000
    }

    /** Everything the settings panel shows: the catalog with the state of every switch it can read directly. */
    fun json(c: Context): String {
        val arr = JSONArray()
        for (it in QuickCatalog.ALL) {
            arr.put(JSONObject()
                .put("id", it.id).put("label", it.label).put("cat", it.cat).put("sec", it.sec).put("kind", it.kind)
                .put("state", stateOf(c, it)).put("options", JSONArray(it.options)))
        }
        return JSONObject().put("items", arr)
            .put("write", canWriteSettings(c)).put("secure", canWriteSecure(c)).put("dnd", dndAllowed(c))
            .put("a11y", PragonAccessibilityService.instance != null).put("grant", AccessFeatures.GRANT_CMD).toString()
    }

    // ───────────── doing ─────────────

    fun run(c: Context, value: String, user: Boolean): CommandExecutor.Res {
        val v = value.trim()
        val cut = v.indexOf(':')
        val name = (if (cut >= 0) v.substring(0, cut) else v).trim()
        val arg = (if (cut >= 0) v.substring(cut + 1) else "").trim().lowercase()
        val item = QuickCatalog.byId(name) ?: QuickCatalog.find(name)
            ?: return no("I don't know a phone setting called \"$name\".")
        if (item.id in QuickCatalog.PRIVATE && !user)
            return no("${item.label} is a privacy switch: only you can change it, by voice or in Settings.", item.label)
        return try { doIt(c.applicationContext, item, arg.ifEmpty { if (item.kind == "btn") "run" else "toggle" }) }
        catch (e: SecurityException) { no("Android would not let me change ${item.label}: ${e.message}", item.label) }
        catch (e: Exception) { no("I couldn't change ${item.label}: ${e.message}", item.label) }
    }

    private fun doIt(c: Context, it: QuickCatalog.Item, arg: String): CommandExecutor.Res {
        val me = Prefs.callMe(c)
        return when (it.kind) {
            "lvl" -> level(c, it, arg, me)
            "ch" -> choice(c, it, arg, me)
            "btn" -> button(c, it, me)
            else -> switch(c, it, arg, me)
        }
    }

    private fun switch(c: Context, it: QuickCatalog.Item, arg: String, me: String): CommandExecutor.Res {
        if (arg == "state") return ok("${it.label} is ${stateOf(c, it).replace("?", "in a state I can't read")}.", it.label)
        val cur = stateOf(c, it)
        val want: String = when (arg) {
            "on", "off" -> arg
            else -> if (cur == "on") "off" else if (cur == "off") "on" else "toggle"
        }
        if (cur == want) return ok("${it.label} is already $want, $me.", it.label)
        val on = want == "on"

        fun done(): CommandExecutor.Res = ok("${it.label} ${if (on) "on" else "off"}, $me.", it.label)
        fun needWrite(): CommandExecutor.Res {
            open(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
            return no("I need permission to change settings. I opened the screen: switch on \"Allow modifying system settings\" for Pragon, then ask again.", it.label)
        }
        fun sys(key: String): CommandExecutor.Res {
            if (!canWriteSettings(c)) return needWrite()
            Settings.System.putInt(c.contentResolver, key, if (on) 1 else 0)
            return done()
        }
        // a setting that Android only lets the adb-granted WRITE_SECURE_SETTINGS change; the tile or Settings screen otherwise
        fun secure(table: String, key: String, onVal: Int, offVal: Int, screen: String): CommandExecutor.Res {
            if (canWriteSecure(c)) {
                try {
                    val cr = c.contentResolver
                    if (table == "global") Settings.Global.putInt(cr, key, if (on) onVal else offVal) else Settings.Secure.putInt(cr, key, if (on) onVal else offVal)
                    return done()
                } catch (e: Exception) { }
            }
            if (it.tile.isNotEmpty() && PragonAccessibilityService.instance != null) return tile(c, it, want, me)
            open(c, Intent(screen))
            return ok("I opened the settings screen, $me. Switch ${it.label} ${if (on) "on" else "off"} there. (To let me do it by voice, run once on your PC: ${AccessFeatures.GRANT_CMD})", it.label)
        }

        return when (it.id) {
            "torch" -> {
                val r = CommandExecutor.run(c, JSONObject().put("action", "flashlight").put("value", want), user = true)
                CommandExecutor.Res(r.ok, r.msg, label = it.label)
            }
            "rotate" -> sys(Settings.System.ACCELEROMETER_ROTATION)
            "haptic" -> sys(Settings.System.HAPTIC_FEEDBACK_ENABLED)
            "touchsounds" -> sys(Settings.System.SOUND_EFFECTS_ENABLED)
            "monoaudio" -> sys("master_mono")
            "autobright" -> {
                if (!canWriteSettings(c)) return needWrite()
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                ok("Adaptive brightness ${if (on) "on" else "off"}, $me.", it.label)
            }
            "dnd" -> {
                if (!dndAllowed(c)) {
                    if (PragonAccessibilityService.instance != null) return tile(c, it, want, me)
                    open(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return no("I need Do Not Disturb access. I opened the screen: allow Pragon, then ask again.", it.label)
                }
                (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                done()
            }
            "airplane", "wifi", "bluetooth", "mobiledata", "hotspot", "location", "nfc", "powersave", "darkmode", "eyecomfort", "extradim",
            "highperf", "workapps", "focus", "zenspace", "kids", "micaccess", "camaccess", "clearvoice" -> tile(c, it, want, me)
            "talkback" -> AccessFeatures.set(c, "talkback", on)
            "selecttospeak" -> AccessFeatures.set(c, "tts", on)
            "caption" -> AccessFeatures.set(c, "caption", on)
            "colorinvert" -> secure("secure", "accessibility_display_inversion_enabled", 1, 0, "android.settings.COLOR_INVERSION_SETTINGS")
            "hightext" -> secure("secure", "high_text_contrast_enabled", 1, 0, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "lockNotif" -> secure("secure", "lock_screen_show_notifications", 1, 0, Settings.ACTION_SOUND_SETTINGS)
            "lockSounds" -> secure("secure", "lockscreen_sounds_enabled", 1, 0, Settings.ACTION_SOUND_SETTINGS)
            "noanim" -> {
                if (canWriteSecure(c)) {
                    try {
                        val cr = c.contentResolver; val f = if (on) 0f else 1f
                        Settings.Global.putFloat(cr, "window_animation_scale", f); Settings.Global.putFloat(cr, "transition_animation_scale", f)
                        Settings.Global.putFloat(cr, "animator_duration_scale", f)
                        return ok("Animations ${if (on) "removed" else "back on"}, $me.", it.label)
                    } catch (e: Exception) { }
                }
                open(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                ok("I opened Accessibility settings, $me. Switch \"Remove animations\" ${if (on) "on" else "off"} there. (For voice control run once on your PC: ${AccessFeatures.GRANT_CMD})", it.label)
            }
            else -> tile(c, it, want, me)
        }
    }

    private fun level(c: Context, it: QuickCatalog.Item, arg: String, me: String): CommandExecutor.Res {
        if (arg == "state" || arg == "toggle") return ok("${it.label} is at ${stateOf(c, it)} percent.", it.label)
        val cur = stateOf(c, it).toIntOrNull() ?: 50
        val pct = when (arg) {
            "max", "on" -> 100
            "min", "off" -> 0
            "up" -> (cur + 10).coerceAtMost(100)
            "down" -> (cur - 10).coerceAtLeast(0)
            else -> arg.removeSuffix("%").toIntOrNull()?.coerceIn(0, 100) ?: return no("How high should the ${it.label.lowercase()} be, from 0 to 100?", it.label)
        }
        if (it.id == "brightness") {
            if (!canWriteSettings(c)) {
                open(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
                return no("I need permission to change the brightness. I opened the screen: switch on \"Allow modifying system settings\" for Pragon, then ask again.", it.label)
            }
            val v = (Math.max(pct, 3) * 255 / 100).coerceIn(8, 255)   // never fully black
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            return ok("Brightness $pct percent, $me.", it.label)
        }
        val stream = when (it.id) { "ringvol" -> AudioManager.STREAM_RING; "alarmvol" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
        val am = audio(c)
        val max = am.getStreamMaxVolume(stream)
        val v = (pct * max + 50) / 100
        try {
            am.setStreamVolume(stream, v, 0)
        } catch (e: SecurityException) {
            if (!dndAllowed(c)) open(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return no("Android won't let me change the ${it.label.lowercase()} while Do Not Disturb is on. I opened its access screen: allow Pragon there.", it.label)
        }
        return ok("${it.label} $pct percent, $me.", it.label)
    }

    private fun choice(c: Context, it: QuickCatalog.Item, arg: String, me: String): CommandExecutor.Res {
        if (arg == "state") return ok("${it.label} is ${stateOf(c, it)}.", it.label)
        when (it.id) {
            "ringmode" -> {
                val mode = when (arg) { "ring", "normal", "off" -> AudioManager.RINGER_MODE_NORMAL; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    "silent", "mute", "on", "toggle" -> AudioManager.RINGER_MODE_SILENT; else -> return no("Ring mode must be ring, vibrate or silent.", it.label) }
                try {
                    audio(c).ringerMode = mode
                } catch (e: SecurityException) {
                    if (PragonAccessibilityService.instance != null && mode == AudioManager.RINGER_MODE_SILENT) return tile(c, it, "run", me)
                    open(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return no("Android needs Do Not Disturb access before I can go silent. I opened the screen: allow Pragon there.", it.label)
                }
                return ok("Phone on ${stateOf(c, it)}, $me.", it.label)
            }
            "fontsize" -> {
                if (!canWriteSettings(c)) {
                    open(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
                    return no("I need permission to change settings. I opened the screen: switch on \"Allow modifying system settings\" for Pragon, then ask again.", it.label)
                }
                val cur = stateOf(c, it)
                val i = QuickCatalog.SIZES.indexOf(cur).coerceAtLeast(1)
                val target = when (arg) {
                    "up" -> QuickCatalog.SIZES[(i + 1).coerceAtMost(3)]
                    "down" -> QuickCatalog.SIZES[(i - 1).coerceAtLeast(0)]
                    in QuickCatalog.SIZES -> arg
                    else -> return no("Text size must be small, default, large or largest.", it.label)
                }
                Settings.System.putFloat(c.contentResolver, Settings.System.FONT_SCALE, fontScale(target))
                return ok("Text size $target, $me.", it.label)
            }
            "timeout" -> {
                if (!canWriteSettings(c)) {
                    open(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
                    return no("I need permission to change settings. I opened the screen: switch on \"Allow modifying system settings\" for Pragon, then ask again.", it.label)
                }
                if (arg !in QuickCatalog.TIMEOUTS) return no("Screen timeout can be 15 or 30 seconds, or 1, 2, 5 or 10 minutes.", it.label)
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs(arg))
                return ok("Screen timeout $arg, $me.", it.label)
            }
        }
        return no("I can't set ${it.label}.", it.label)
    }

    private fun button(c: Context, it: QuickCatalog.Item, me: String): CommandExecutor.Res {
        when (it.id) {
            "clearall" -> {
                val svc = PragonAccessibilityService.instance ?: return no("Turn on the PragonMobile accessibility service first (Settings > Accessibility).", it.label)
                return when (svc.clearNotifications()) {
                    "cleared" -> ok("Notifications cleared, $me.", it.label)
                    "noopen" -> no("I couldn't open the notification shade (is the screen locked?).", it.label)
                    else -> ok("I found nothing to clear, $me.", it.label)
                }
            }
            "notifsettings" -> {
                if (!open(c, Intent("android.settings.NOTIFICATION_SETTINGS"))) open(c, Intent(Settings.ACTION_SETTINGS))
                return ok("Notification settings opened, $me.", it.label)
            }
            "screenshot" -> {
                val r = CommandExecutor.run(c, JSONObject().put("action", "key").put("value", "screenshot"), user = true)
                return CommandExecutor.Res(r.ok, r.msg, label = it.label)
            }
        }
        return tile(c, it, "run", me)
    }

    /** Presses the phone's own tile through the accessibility service. */
    private fun tile(c: Context, it: QuickCatalog.Item, want: String, me: String): CommandExecutor.Res {
        val svc = PragonAccessibilityService.instance
            ?: return no("To switch ${it.label} I need the PragonMobile accessibility service: turn it on in Settings > Accessibility.", it.label)
        val labels = (it.tile + it.label).distinct()
        return when (val r = svc.tileAction(labels, want)) {
            "on", "off" -> ok("${it.label} $r, $me.", it.label)
            "already on", "already off" -> ok("${it.label} is $r, $me.", it.label)
            "pressed" -> ok(if (want == "run") "${it.label} opened, $me." else "I pressed the ${it.label} tile, $me. I can't tell if it is on or off now.", it.label)
            "noopen" -> no("I couldn't open the Quick Settings panel (is the screen locked?).", it.label)
            else -> no("I couldn't find a \"${it.label}\" tile in your Quick Settings. Add it with the pencil (edit) button of the panel, then ask again.", it.label)
        }
    }
}
