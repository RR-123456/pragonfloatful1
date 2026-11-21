package com.pragon.mobile

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * v18: Drafter for Tools. Writes an email, letter, passage, summary or points list from what the user types.
 * Uses the same Pragon AI key and Gemini model as live web answers. It keeps line breaks (unlike the voice answers).
 * Nothing is saved; the text is shown in Tools and the user copies or shares it.
 */
object Drafter {
    private val JSONT = "application/json; charset=utf-8".toMediaType()

    fun instruction(kind: String, tone: String): String {
        val t = if (tone.isBlank()) "clear and polite" else tone
        return when (kind) {
            "email" -> "Write an email in a $t tone. First line: 'Subject: ...'. Then a blank line, then the body with greeting and sign-off."
            "letter" -> "Write a letter in a $t tone with date placeholder, greeting, body paragraphs and a sign-off."
            "passage" -> "Write a well-structured passage in a $t tone. Use short paragraphs."
            "summary" -> "Summarise the text below in about one fifth of its length, keeping the key facts. Tone: $t."
            "points" -> "Turn the text below into clear bullet points (start each line with '- '), grouped under short headings if useful. Tone: $t."
            else -> "Help with the request below in a $t tone."
        }
    }

    /** Blocking call: run it on a background thread. Returns (ok, text). */
    fun draft(ctx: Context, kind: String, tone: String, input: String): Pair<Boolean, String> {
        if (input.isBlank()) return false to "Type what it should be about first."
        val key = Prefs.effectiveAiKey(ctx)
        if (key.isBlank()) return false to "Drafting needs a Pragon AI key. Add yours in Settings."
        val model = Prefs.aiModelWorking(ctx).ifBlank { Prefs.aiModel(ctx) }
        val system = "You are a writing assistant. " + instruction(kind, tone) +
            " Reply with the text only. No preface, no notes after it, no markdown symbols other than '- ' bullets."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", input.take(6000))))))
            .put("generationConfig", JSONObject().put("temperature", 0.5).put("maxOutputTokens", 1200))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSONT))
            .build()
        return try {
            Web.http.newCall(req).execute().use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = try { JSONObject(raw).getJSONObject("error").optString("message") } catch (e: Exception) { "The server answered ${r.code}." }
                    return false to msg
                }
                val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                val t = sb.toString().trim()
                if (t.isEmpty()) false to "Nothing came back. Try again." else true to t
            }
        } catch (e: Exception) {
            false to Web.why(e)
        }
    }
}
