package com.kaizen.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class KaizenCore(private val context: Context) {

    private val preferences =
        context.getSharedPreferences("kaizen_memory", Context.MODE_PRIVATE)

    private val conversation = mutableListOf<String>()

    init {
        loadMemory()
    }

    fun respond(input: String): String {

        val command = input.trim()

        if (command.isEmpty()) {
            return "I'm listening."
        }

        remember("USER: $command")

        val response = processCommand(command)

        remember("KAIZEN: $response")

        saveMemory()

        return response
    }

    private fun processCommand(input: String): String {

        val command = input.lowercase().trim()

        return when {

            command == "hello" ||
            command == "hi" ||
            command.contains("good morning") ||
            command.contains("good afternoon") ||
            command.contains("good evening") -> {

                "Hello, ma'am. How may I help you today?"
            }

            command.contains("who are you") -> {

                "I'm Kaizen, your personal assistant."
            }

            command.contains("what can you do") -> {

                "I can help you with commands, device functions, conversations, reminders, and other tasks as we add more abilities."
            }

            command.contains("how are you") -> {

                "I'm functioning perfectly, ma'am."
            }

            command.contains("thank") -> {

                "You're welcome, ma'am."
            }

            command.contains("turn on bluetooth") ||
            command.contains("enable bluetooth") -> {

                "Bluetooth command detected. I'm ready to control Bluetooth."
            }

            command.contains("turn off bluetooth") ||
            command.contains("disable bluetooth") -> {

                "Bluetooth off command detected. I'm ready to control Bluetooth."
            }

            command.contains("open calendar") ||
            command.contains("calendar") -> {

                "Opening your calendar is ready to be connected."
            }

            command.contains("open camera") ||
            command.contains("camera") -> {

                "Camera command detected."
            }

            command.contains("location") -> {

                "Location command detected."
            }

            command.contains("wifi") ||
            command.contains("wi-fi") -> {

                "Wi-Fi command detected."
            }

            command.contains("remember") -> {

                "I'll keep that in Kaizen's local memory."
            }

            command.contains("what do you remember") -> {

                getMemorySummary()
            }

            command == "clear memory" -> {

                clearMemory()
                "My local conversation memory has been cleared."
            }

            command == "exit" ||
            command == "quit" ||
            command == "goodbye" -> {

                "I'll be here whenever you need me, ma'am."
            }

            else -> {

                "I understand you said: \"$input\". I'm still learning how to handle that command."
            }
        }
    }

    private fun remember(message: String) {

        conversation.add(message)

        // Keep memory from becoming unnecessarily large.
        if (conversation.size > 50) {
            conversation.removeAt(0)
        }
    }

    private fun saveMemory() {

        val array = JSONArray()

        conversation.forEach {
            array.put(it)
        }

        preferences.edit()
            .putString("conversation", array.toString())
            .apply()
    }

    private fun loadMemory() {

        val saved = preferences.getString("conversation", null)
            ?: return

        try {

            val array = JSONArray(saved)

            for (i in 0 until array.length()) {
                conversation.add(array.getString(i))
            }

        } catch (e: Exception) {

            conversation.clear()
        }
    }

    private fun getMemorySummary(): String {

        if (conversation.isEmpty()) {
            return "I don't have any previous conversation stored yet."
        }

        val recent = conversation.takeLast(6)

        return buildString {

            append("Here's what I remember from our recent conversation:\n\n")

            recent.forEach {
                append(it)
                append("\n")
            }
        }
    }

    private fun clearMemory() {

        conversation.clear()

        preferences.edit()
            .clear()
            .apply()
    }
}
