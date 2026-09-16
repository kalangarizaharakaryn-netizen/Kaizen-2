package com.kaizen.assistant

class KaizenCore {

    private val commandProcessor = CommandProcessor()

    fun processCommand(command: String): String {
        val input = command.trim().lowercase()

        return when {

            input.contains("good morning") ||
            input.contains("good afternoon") ||
            input.contains("good evening") -> {
                "Hello, ma'am. How may I help you today?"
            }

            input.contains("who are you") -> {
                "I'm Kaizen, your personal assistant."
            }

            input.contains("what can you do") -> {
                "I can help you with conversations and device commands."
            }

            input.contains("how are you") -> {
                "I'm functioning perfectly, ma'am."
            }

            input.contains("thank") -> {
                "You're welcome, ma'am."
            }

            input.contains("turn on bluetooth") ||
            input.contains("enable bluetooth") -> {
                "Bluetooth command detected."
            }

            input.contains("turn off bluetooth") ||
            input.contains("disable bluetooth") -> {
                "Bluetooth off command detected."
            }

            input.contains("open calendar") -> {
                "Opening your calendar."
            }

            input.contains("open camera") -> {
                "Opening your camera."
            }

            input.contains("location") -> {
                "Location command detected."
            }

            input.contains("wifi") ||
            input.contains("wi-fi") -> {
                "Wi-Fi command detected."
            }

            input.contains("remember") -> {
                "I'll keep that in Kaizen's local memory."
            }

            else -> {
                "I'm listening, ma'am. How may I help you?"
            }
        }
    }
}
