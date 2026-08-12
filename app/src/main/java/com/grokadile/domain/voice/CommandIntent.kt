package com.grokadile.domain.voice

/**
 * Structured intents produced from natural-language chat or voice input.
 * The dispatcher maps these onto agent tasks, direct Grok calls, or local control.
 */
sealed class CommandIntent {
    /** Conversational question answered immediately via Grok (no agent queue). */
    data class AskGrok(val prompt: String) : CommandIntent()

    data class ReadScreen(val mode: String = "text") : CommandIntent()

    data class TapText(val text: String, val exact: Boolean = false) : CommandIntent()

    data class TypeText(val text: String) : CommandIntent()

    /** Accessibility global action: BACK, HOME, RECENTS, NOTIFICATIONS, … */
    data class GlobalAction(val name: String) : CommandIntent()

    data object StartAutonomy : CommandIntent()

    data object StopAutonomy : CommandIntent()

    data class Echo(val message: String) : CommandIntent()

    /** Could not map confidently; [raw] is echoed back for clarification. */
    data class Unknown(val raw: String) : CommandIntent()
}
