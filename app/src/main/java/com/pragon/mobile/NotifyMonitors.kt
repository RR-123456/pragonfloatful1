package com.pragon.mobile

import android.app.Notification
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * v18: Notification monitors. Pragon posts a notification ONLY for the monitors the user switched on.
 * Privacy rules (enforced here, not just promised):
 *  - The message body is never read, stored or shown. Only the sender / title is checked against the user's "only from" filter.
 *  - Nothing is logged or saved except the user's own monitor settings.
 *  - Off by default: with no monitor switched on, nothing is posted.
 */
object NotifyRules {
    private const val FILE = "pragon_monitors"

    /** id -> label. "from" = the monitor accepts a sender filter. */
    val SOURCES = listOf(
        Triple("calls_missed", "Missed calls", true),
        Triple("calls_incoming", "Incoming calls", true),
        Triple("whatsapp", "WhatsApp", true),
        Triple("messages", "Messages", true),
        Triple("gmail", "Gmail", true),
        Triple("alarm", "Alarm apps", false),
        Triple("battery", "Battery reminder", false)
    )

    private val DIALER = setOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.oplus.dialer", "com.coloros.dialer")
    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val MESSAGES = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging", "com.oplus.mms", "com.coloros.mms")
    private val ALARM = setOf("com.google.android.deskclock", "com.android.deskclock", "com.coloros.alarmclock", "com.oppo.alarmclock", "com.oplus.alarmclock")

    fun label(id: String): String = SOURCES.firstOrNull { it.first == id }?.second ?: id

    /** Which monitor a posted notification belongs to, or null. Looks only at the package and category. */
    fun sourceOf(pkg: String, category: String?): String? = when {
        category == Notification.CATEGORY_CALL && pkg in DIALER -> "calls_incoming"
        pkg in WHATSAPP -> "whatsapp"
        pkg in MESSAGES -> "messages"
        pkg == "com.google.android.gm" -> "gmail"
        pkg in ALARM -> "alarm"
        else -> null
    }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Current settings: { "gmail": {"on": true, "from": "GitHub"}, ... }. */
    fun json(c: Context): JSONObject {
        val raw = try { JSONObject(sp(c).getString("rules", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val out = JSONObject()
        SOURCES.forEach { (id, _, _) ->
            val r = raw.optJSONObject(id)
            out.put(id, JSONObject().put("on", r?.optBoolean("on", false) ?: false).put("from", r?.optString("from", "") ?: ""))
        }
        return out
    }

    fun set(c: Context, id: String, on: Boolean, from: String) {
        if (SOURCES.none { it.first == id }) return
        val all = json(c)
        all.put(id, JSONObject().put("on", on).put("from", from.trim().take(60)))
        sp(c).edit().putString("rules", all.toString()).apply()
    }

    fun rule(c: Context, id: String): Pair<Boolean, String> {
        val r = json(c).optJSONObject(id) ?: return false to ""
        return r.optBoolean("on", false) to r.optString("from", "")
    }
}

/**
 * Needs "Notification access" for Pragon (Settings > Notifications > Notification access).
 * It only posts Pragon's own reminder, e.g. "WhatsApp: From Mom". The message text is never used.
 */
class PragonNotifyService : NotificationListenerService() {

    private var observer: ContentObserver? = null
    private var lastCallId = 0L
    /** True once the call log was read successfully. Until then (no permission yet) nothing is posted, so old missed calls never flood in. */
    private var seeded = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        val id = latestCallId()
        if (id >= 0) { lastCallId = id; seeded = true }
        try {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { checkMissed() }
            }
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, o)
            observer = o
        } catch (_: Exception) { }
    }

    override fun onListenerDisconnected() {
        try { observer?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) { }
        observer = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val src = NotifyRules.sourceOf(sbn.packageName, sbn.notification.category) ?: return
            val (on, from) = NotifyRules.rule(this, src)
            if (!on) return
            // Only the sender / title is looked at. The body (EXTRA_TEXT) is never read.
            val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            if (from.isNotEmpty() && !title.contains(from, ignoreCase = true)) return
            post(src, title)
        } catch (_: Exception) { }
    }

    /** Newest call-log id, 0 for an empty log, -1 when the log cannot be read (permission missing). */
    private fun latestCallId(): Long = try {
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: -1L
    } catch (_: Exception) { -1L }

    /** New missed calls since the last check. Only the caller name or number is used. */
    private fun checkMissed() {
        try {
            if (!seeded) {
                // Permission may have been granted after the service connected: start from "now", do not replay history.
                val id = latestCallId()
                if (id >= 0) { lastCallId = id; seeded = true }
                return
            }
            val (on, from) = NotifyRules.rule(this, "calls_missed")
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                "${CallLog.Calls._ID} > ?", arrayOf(lastCallId.toString()), "${CallLog.Calls._ID} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    lastCallId = maxOf(lastCallId, cur.getLong(0))
                    if (cur.getInt(1) != CallLog.Calls.MISSED_TYPE || !on) continue
                    val who = cur.getString(2) ?: cur.getString(3) ?: "Unknown"
                    if (from.isNotEmpty() && !who.contains(from, ignoreCase = true)) continue
                    post("calls_missed", who)
                }
            }
        } catch (_: Exception) { }
    }

    private fun post(src: String, who: String) {
        val label = NotifyRules.label(src)
        val text = if (who.isEmpty()) "New $label" else "From $who"
        Notes.post(this, "pragon_monitors", "Pragon monitors", label, text, 9700 + (src.hashCode() and 0xff))
    }
}
