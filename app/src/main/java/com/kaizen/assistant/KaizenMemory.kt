package com.kaizen.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class KaizenMemory(context: Context) {

    private val prefs =
        context.getSharedPreferences("kaizen_memory", Context.MODE_PRIVATE)

    fun remember(key: String, value: String) {
        val memories = getMemories()
        memories.put(
            key.trim().lowercase(),
            value.trim()
        )

        prefs.edit()
            .putString("memories", memories.toString())
            .apply()
    }

    fun recall(key: String): String? {
        return getMemories().optString(
            key.trim().lowercase(),
            null
        )
    }

    fun all(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val memories = getMemories()

        val keys = memories.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = memories.optString(key)
        }

        return result
    }

    fun clear() {
        prefs.edit().remove("memories").apply()
    }

    private fun getMemories(): JSONObject {
        val saved = prefs.getString("memories", null)

        return try {
            if (saved.isNullOrBlank()) {
                JSONObject()
            } else {
                JSONObject(saved)
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
