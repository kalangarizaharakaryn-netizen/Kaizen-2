package com.kaizen.assistant

/** Handles the handful of things Kaizen should answer instantly, offline, without spending an API call. */
object LocalReplies {

    fun tryReply(text: String): String? {
        val t = text.trim().lowercase().trim('.', '!', '?')
        return when {
            Regex("^(hi|hello|hey|hi kaizen|hello kaizen|hey kaizen)$").matches(t) ->
                "Hello, ma'am. What can I do for you?"
            Regex("^(good morning)( kaizen)?$").matches(t) ->
                "Good morning, ma'am."
            Regex("^(good night)( kaizen)?$").matches(t) ->
                "Good night, ma'am."
            Regex("^(thanks|thank you|cheers)( kaizen)?$").matches(t) ->
                "Any time, ma'am."
            Regex("^(who are you|what are you|what is kaizen)\\??$").matches(t) ->
                "I'm Kaizen — your assistant, ma'am. Offline I can handle hardware, calculations, and quick tasks; " +
                "connected, I can also research, translate, and hold a full conversation."
            else -> null
        }
    }
}
