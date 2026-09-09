            package com.kaizen.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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

    suspend fun ask(history: List<Pair<String, String>>, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "user").put("content", SYSTEM_PROMPT))
            messages.put(JSONObject().put("role", "assistant").put("content", "Understood, ma'am."))
            history.forEach { (role, content) ->
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            val payload = JSONObject()
                .put("model", "claude-sonnet-4-6")
                .put("max_tokens", 1024)
                .put("messages", messages)

            runRequest(payload, apiKey)
        }

    suspend fun askVision(base64Jpeg: String, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val content = JSONArray()
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source", JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", base64Jpeg)
                        )
                )
                .put(JSONObject().put("type", "text").put("text", "Briefly describe what you see, in your normal voice."))

            val messages = JSONArray()
                .put(JSONObject().put("role", "user").put("content", content))

            val payload = JSONObject()
                .put("model", "claude-sonnet-4-6")
                .put("max_tokens", 600)
                .put("messages", messages)

            runRequest(payload, apiKey)
        }

    private fun runRequest(payload: JSONObject, apiKey: String): String {
        val body = payload.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("content-type", "application/json")
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("x-api-key", apiKey)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: return "No response body from the API."
                val json = JSONObject(respBody)

                if (!response.isSuccessful || json.optString("type") == "error") {
                    val detail = json.optJSONObject("error")?.optString("message")
                    return "My reasoning core returned an error: ${detail ?: "HTTP ${response.code}"}"
                }

                val contentArr = json.optJSONArray("content") ?: return "No content in that response."
                val sb = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val block = contentArr.getJSONObject(i)
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) "That came back with no readable text." else text
            }
        } catch (e: Exception) {
            "Connection to my reasoning core failed: ${e.message}"
        }
    }
}
