package com.grokadile.domain.voice

import com.grokadile.core.common.AppResult
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.repository.GrokRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns free-form chat / voice utterances into [CommandIntent]s.
 *
 * Resolution order:
 * 1. Fast local regex / keyword rules (offline, instant)
 * 2. Optional Grok JSON intent parse when [useLlmFallback] is true and local rules miss
 */
@Singleton
class CommandInterpreter @Inject constructor(
    private val grok: GrokRepository,
    private val json: Json,
) {

    suspend fun interpret(
        utterance: String,
        useLlmFallback: Boolean = true,
        model: String = "grok-2-latest",
    ): CommandIntent {
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) return CommandIntent.Unknown("")

        localInterpret(trimmed)?.let { return it }

        if (!useLlmFallback) return CommandIntent.AskGrok(trimmed)

        return llmInterpret(trimmed, model) ?: CommandIntent.AskGrok(trimmed)
    }

    /** Pure local rules — exposed for unit tests without a network. */
    fun localInterpret(utterance: String): CommandIntent? {
        val text = utterance.trim().removeWakePrefix().trim()
        if (text.isBlank()) return null
        val lower = text.lowercase()

        when {
            lower.matches(START_AUTONOMY) -> return CommandIntent.StartAutonomy
            lower.matches(STOP_AUTONOMY) -> return CommandIntent.StopAutonomy
            lower.matches(GO_BACK) -> return CommandIntent.GlobalAction("BACK")
            lower.matches(GO_HOME) -> return CommandIntent.GlobalAction("HOME")
            lower.matches(OPEN_RECENTS) -> return CommandIntent.GlobalAction("RECENTS")
            lower.matches(OPEN_NOTIFICATIONS) -> return CommandIntent.GlobalAction("NOTIFICATIONS")
        }

        when {
            lower.matches(Regex("""^(read|dump|capture) (the )?screen( as)?( text| hierarchy| focused)?$""")) ||
                lower.matches(Regex("""^what('?s| is) on (the )?screen$""")) ||
                lower.matches(Regex("""^screen dump( text| hierarchy| focused)?$""")) -> {
                val mode = when {
                    lower.contains("hierarchy") -> "hierarchy"
                    lower.contains("focused") -> "focused"
                    else -> "text"
                }
                return CommandIntent.ReadScreen(mode = mode)
            }
        }

        TAP_TEXT.find(text)?.let { match ->
            val target = match.groupValues[2].trim().trimQuotes()
            if (target.isNotBlank()) return CommandIntent.TapText(target)
        }

        TYPE_TEXT.find(text)?.let { match ->
            val typed = match.groupValues[2].trim().trimQuotes()
            if (typed.isNotBlank()) return CommandIntent.TypeText(typed)
        }

        ECHO.find(text)?.let { match ->
            val msg = match.groupValues[1].trim().trimQuotes()
            if (msg.isNotBlank()) return CommandIntent.Echo(msg)
        }

        when {
            lower.matches(CLIPBOARD_GET) -> return CommandIntent.ClipboardGet
            lower.matches(DEVICE_STATUS) -> return CommandIntent.DeviceStatus
            lower.matches(RETRY_FAILED) -> return CommandIntent.RetryFailed
        }

        CLIPBOARD_SET.find(text)?.let { match ->
            val copied = match.groupValues[1].trim().trimQuotes()
            if (copied.isNotBlank()) return CommandIntent.ClipboardSet(copied)
        }

        SEARCH_MEMORY.find(text)?.let { match ->
            val query = match.groupValues[1].trim().trimQuotes()
            if (query.isNotBlank()) return CommandIntent.SearchMemory(query)
        }

        REMEMBER.find(text)?.let { match ->
            val note = match.groupValues[2].trim().trimQuotes()
            if (note.isNotBlank()) return CommandIntent.Remember(note)
        }

        PLAN.find(text)?.let { match ->
            val goal = match.groupValues[2].trim().trimQuotes()
            if (goal.isNotBlank()) return CommandIntent.Plan(goal)
        }

        LAUNCH.find(text)?.let { match ->
            val target = match.groupValues[2].trim().trimQuotes()
            if (target.isNotBlank() && target.lowercase() !in LAUNCH_BLOCKLIST) {
                return CommandIntent.LaunchApp(target)
            }
        }

        ASK_EXPLICIT.find(text)?.let { match ->
            val prompt = match.groupValues[1].trim()
            if (prompt.isNotBlank()) return CommandIntent.AskGrok(prompt)
        }

        // Questions / conversational prompts default to AskGrok without LLM.
        if (lower.endsWith("?") ||
            lower.startsWith("what ") ||
            lower.startsWith("who ") ||
            lower.startsWith("why ") ||
            lower.startsWith("how ") ||
            lower.startsWith("when ") ||
            lower.startsWith("where ") ||
            lower.startsWith("tell me") ||
            lower.startsWith("explain")
        ) {
            return CommandIntent.AskGrok(text)
        }

        return null
    }

    private suspend fun llmInterpret(utterance: String, model: String): CommandIntent? {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatRole.USER, utterance),
            ),
            model = model,
            temperature = 0.1,
            maxTokens = 200,
        )
        return when (val result = grok.chat(request)) {
            is AppResult.Success -> parseLlmJson(result.data.content)
            is AppResult.Failure -> null
        }
    }

    private fun parseLlmJson(raw: String): CommandIntent? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val dto = runCatching { json.decodeFromString<LlmIntentDto>(cleaned) }.getOrNull()
            ?: return null
        return when (dto.intent.lowercase()) {
            "ask", "ask_grok", "chat" ->
                CommandIntent.AskGrok(dto.prompt ?: dto.text ?: cleaned)
            "read_screen", "screen", "dump" ->
                CommandIntent.ReadScreen(dto.mode ?: "text")
            "tap_text", "tap", "click" ->
                dto.text?.takeIf { it.isNotBlank() }?.let { CommandIntent.TapText(it, dto.exact == true) }
            "type", "type_text" ->
                dto.text?.takeIf { it.isNotBlank() }?.let { CommandIntent.TypeText(it) }
            "global", "global_action" ->
                dto.name?.takeIf { it.isNotBlank() }?.let {
                    CommandIntent.GlobalAction(it.uppercase())
                }
            "start_autonomy", "start" -> CommandIntent.StartAutonomy
            "stop_autonomy", "stop" -> CommandIntent.StopAutonomy
            "echo" ->
                dto.text?.takeIf { it.isNotBlank() }?.let { CommandIntent.Echo(it) }
            "remember" ->
                dto.text?.takeIf { it.isNotBlank() }?.let { CommandIntent.Remember(it) }
            "search_memory", "recall" ->
                (dto.query ?: dto.text)?.takeIf { it.isNotBlank() }
                    ?.let { CommandIntent.SearchMemory(it) }
            "plan", "planner" ->
                (dto.prompt ?: dto.text)?.takeIf { it.isNotBlank() }
                    ?.let { CommandIntent.Plan(it) }
            "clipboard_get", "clipboard" -> CommandIntent.ClipboardGet
            "clipboard_set", "copy" ->
                dto.text?.takeIf { it.isNotBlank() }?.let { CommandIntent.ClipboardSet(it) }
            "launch", "open_app" ->
                (dto.text ?: dto.name)?.takeIf { it.isNotBlank() }
                    ?.let { CommandIntent.LaunchApp(it) }
            "device_status", "health" -> CommandIntent.DeviceStatus
            "retry_failed" -> CommandIntent.RetryFailed
            "unknown" -> CommandIntent.Unknown(dto.prompt ?: dto.text ?: "")
            else -> null
        }
    }

    @Serializable
    private data class LlmIntentDto(
        val intent: String,
        val prompt: String? = null,
        val text: String? = null,
        val query: String? = null,
        val mode: String? = null,
        val name: String? = null,
        val exact: Boolean? = null,
    )

    companion object {
        val WAKE_PHRASES: List<String> = listOf(
            "hey grokadile",
            "hi grokadile",
            "okay grokadile",
            "ok grokadile",
            "hey grok",
            "hi grok",
            "okay grok",
            "ok grok",
        )

        private val START_AUTONOMY = Regex(
            """^(start|enable|turn on)\s+(autonomy|autonomous( mode)?|agents?|the agents?)$""",
        )
        private val STOP_AUTONOMY = Regex(
            """^(stop|disable|turn off)\s+(autonomy|autonomous( mode)?|agents?|the agents?)$""",
        )
        private val GO_BACK = Regex("""^(go )?back$|^press back$""")
        private val GO_HOME = Regex("""^(go )?home$|^press home$""")
        private val OPEN_RECENTS = Regex("""^(open )?recents$|^show recent( apps)?$""")
        private val OPEN_NOTIFICATIONS = Regex("""^(open |show )?notifications?$""")
        private val TAP_TEXT = Regex(
            """^(tap|click|press|hit)\s+(?:(?:on|the)\s+)?["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE,
        )
        private val TYPE_TEXT = Regex(
            """^(type|enter|input)\s+["']?(.+?)["']?$""",
            RegexOption.IGNORE_CASE,
        )
        private val ECHO = Regex("""^echo\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val ASK_EXPLICIT = Regex(
            """^(?:ask(?:\s+grok)?|hey grok,?)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val CLIPBOARD_GET = Regex(
            """^(what('?s| is) on (the )?clipboard|read (the )?clipboard|clipboard)$""",
        )
        private val CLIPBOARD_SET = Regex(
            """^(?:copy|set clipboard(?: to)?|put on (?:the )?clipboard)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val DEVICE_STATUS = Regex(
            """^(device (status|health)|battery( status)?|phone status|health check)$""",
        )
        private val RETRY_FAILED = Regex(
            """^retry failed( tasks)?$|^retry failures$""",
        )
        private val SEARCH_MEMORY = Regex(
            """^(?:recall|search memory(?:\s+for)?|what do you remember about)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val REMEMBER = Regex(
            """^(remember|note that|save(?:\s+to memory)?)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val PLAN = Regex(
            """^(plan|figure out how to|make a plan to|do this:)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val LAUNCH = Regex(
            """^(open|launch|start)\s+(?:the\s+)?(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val LAUNCH_BLOCKLIST = setOf(
            "recents", "recent apps", "notifications", "notification",
            "autonomy", "autonomous mode", "agents", "the agents",
        )

        private val SYSTEM_PROMPT = """
You are Grokadile's on-device command router. Map the user utterance to ONE JSON object.
No markdown, no explanation — JSON only with this shape:
{"intent":"<ask|read_screen|tap_text|type|global|start_autonomy|stop_autonomy|echo|remember|search_memory|plan|clipboard_get|clipboard_set|launch|device_status|retry_failed|unknown>",
 "prompt":"...", "text":"...", "query":"...", "mode":"text|hierarchy|focused", "name":"BACK|HOME|RECENTS|NOTIFICATIONS", "exact":false}

Rules:
- Device UI actions (tap/click/type/back/home/read screen) → matching intent.
- Start/stop agents/autonomy → start_autonomy / stop_autonomy.
- Remember/save a fact → remember. Recall/search memory → search_memory.
- Multi-step goals ("plan …", "figure out how to …") → plan.
- Open/launch an app → launch with text = app name.
- Clipboard read/write → clipboard_get / clipboard_set.
- Battery/device health → device_status. Retry failed tasks → retry_failed.
- General questions or chat → intent "ask" with prompt set to the user text.
- If unclear, use "unknown".
""".trimIndent()
    }
}

private fun String.removeWakePrefix(): String {
    var s = this.trim()
    for (phrase in CommandInterpreter.WAKE_PHRASES.sortedByDescending { it.length }) {
        if (s.startsWith(phrase, ignoreCase = true)) {
            s = s.substring(phrase.length).trimStart(',', ' ', '?', '!')
            break
        }
    }
    return s
}

private fun String.trimQuotes(): String =
    trim().trim('"', '\'', '“', '”', '‘', '’')
