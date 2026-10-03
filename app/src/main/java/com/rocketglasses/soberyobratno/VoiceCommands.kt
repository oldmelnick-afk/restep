package com.rocketglasses.soberyobratno

internal object VoiceCommands {
    val russianSend = setOf("отправить", "отправь", "отправь фото", "отправить фото")
    val englishSend = setOf("send", "send note", "send photo", "save note", "save")
    private val endings = (russianSend + englishSend).sortedByDescending { it.length }
    data class Submission(val note: String, val send: Boolean)

    fun submission(text: String, commandConfirmed: Boolean = false): Submission {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        // Alternate spellings only remove a trailing transcription artifact after
        // the dedicated command recognizer has positively detected a send command.
        val candidates = if (commandConfirmed) endings + listOf("сэнд", "сент", "сэн", "sent", "sand") else endings
        for (ending in candidates) {
            if (clean.equals(ending, ignoreCase = true)) return Submission("", true)
            if (clean.endsWith(" $ending", ignoreCase = true))
                return Submission(clean.dropLast(ending.length).trimEnd(), true)
        }
        return Submission(clean, commandConfirmed)
    }

    fun isSendCommand(text: String, language: String, confidence: Double): Boolean =
        confidence >= 0.85 && text.trim() in if (language == "ru") russianSend else englishSend
}
