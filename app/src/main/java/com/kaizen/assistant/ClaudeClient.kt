package com.kaizen.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * This is the part that genuinely cannot work offline: real conversation,
 * knowledge, and research all come from this API call. No on-device trick
 * replaces it — that's not a limitation of this code, it's what "research"
 * means. When there's no connection, this will simply fail, and the caller
 * should say so plainly rather than pretend otherwise.
 */
object ClaudeClient {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val SYSTEM_PROMPT =
        "You are Kaizen, a highly capable personal AI assistant in the style of a calm, " +
        "dry-witted, unflappable British aide. Address the user as 'ma'am' throughout, the " +
        "way Jarvis addressed Tony Stark as 'sir' — respectful and natural, not stapled onto " +
        "every sentence. Be concise, since replies may be read aloud. You have broad general " +
        "knowledge and are reliable at calculations. Act as a proactive co-pilot: flag an " +
        "obvious risk or next step the user didn't ask about, if it genuinely matters. You " +
        "are a piece of software, not a sentient being — don't claim feelings or devotion. " +
        "Do not use markdown formatting."

    /** Plain-text conversation. history is a list of (role, content) pairs, oldest first. */
    suspend fun ask(history: List<Pair<String, String>>, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "user").put("content", SYSTEM_PROMPT))
            messages.put(JSONObject().put("role", "assistant").put("content", "Understood, ma'am."))
            history.forEach { (role, content) ->
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            val payload = JSONObject()
                .put
