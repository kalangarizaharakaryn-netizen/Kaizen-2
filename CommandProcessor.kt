package com.kaizen.assistant

class CommandProcessor {

    fun classify(command: String): CommandType {

        val input = command.lowercase().trim()

        return when {

            input.contains("bluetooth") ->
                CommandType.BLUETOOTH

            input.contains("wifi") ||
            input.contains("wi-fi") ->
                CommandType.WIFI

            input.contains("camera") ->
                CommandType.CAMERA

            input.contains("calendar") ->
                CommandType.CALENDAR

            input.contains("location") ->
                CommandType.LOCATION

            input.contains("storage") ->
                CommandType.STORAGE

            input.contains("settings") ->
                CommandType.SETTINGS

            input.contains("instagram") ->
                CommandType.INSTAGRAM

            else ->
                CommandType.CONVERSATION
        }
    }
}

enum class CommandType {

    BLUETOOTH,
    WIFI,
    CAMERA,
    CALENDAR,
    LOCATION,
    STORAGE,
    SETTINGS,
    INSTAGRAM,
    CONVERSATION
}
