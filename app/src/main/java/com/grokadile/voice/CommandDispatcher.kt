package com.grokadile.voice

import com.grokadile.agent.AgentController
import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.agent.builtin.GrokChatAgent
import com.grokadile.agent.builtin.ScreenReadingAgent
import com.grokadile.agent.builtin.ScreenTapAgent
import com.grokadile.core.common.AppResult
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.GrokRepository
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.domain.repository.TaskRepository
import com.grokadile.domain.voice.CommandIntent
import com.grokadile.domain.voice.CommandInterpreter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a chat / voice command, ready to show and optionally speak. */
data class CommandReply(
    val spoken: String,
    val detail: String = spoken,
    val taskId: String? = null,
)

/**
 * Interprets natural-language commands and either answers via Grok immediately
 * or enqueues the matching agent task (ensuring the orchestrator is running).
 */
@Singleton
class CommandDispatcher @Inject constructor(
    private val interpreter: CommandInterpreter,
    private val controller: AgentController,
    private val grok: GrokRepository,
    private val settingsRepository: SettingsRepository,
    private val taskRepository: TaskRepository,
    private val logger: GrokLogger,
) {

    suspend fun dispatch(utterance: String): CommandReply {
        val settings = settingsRepository.current()
        val intent = interpreter.interpret(
            utterance = utterance,
            useLlmFallback = settings.hasApiKey,
            model = settings.grokModel,
        )
        logger.i(TAG, "Intent=${intent::class.simpleName} for \"$utterance\"")
        return execute(intent)
    }

    suspend fun execute(intent: CommandIntent): CommandReply = when (intent) {
        is CommandIntent.AskGrok -> askGrok(intent.prompt)
        is CommandIntent.ReadScreen -> enqueueAndAwait(
            agentId = ScreenReadingAgent.ID,
            title = "Voice: read screen",
            payload = buildJsonObject {
                put("mode", intent.mode)
                put("store", true)
            }.toString(),
            waiting = "Reading the screen…",
            successPrefix = "Here's what's on screen",
        )
        is CommandIntent.TapText -> enqueueAndAwait(
            agentId = ScreenTapAgent.ID,
            title = "Voice: tap ${intent.text}",
            payload = buildJsonObject {
                put("action", "click_text")
                put("text", intent.text)
                put("exact", intent.exact)
            }.toString(),
            waiting = "Tapping \"${intent.text}\"…",
            successPrefix = "Tapped \"${intent.text}\"",
        )
        is CommandIntent.TypeText -> enqueueAndAwait(
            agentId = ScreenTapAgent.ID,
            title = "Voice: type text",
            payload = buildJsonObject {
                put("action", "type")
                put("text", intent.text)
            }.toString(),
            waiting = "Typing…",
            successPrefix = "Typed that in",
        )
        is CommandIntent.GlobalAction -> enqueueAndAwait(
            agentId = ScreenTapAgent.ID,
            title = "Voice: ${intent.name}",
            payload = buildJsonObject {
                put("action", "global")
                put("name", intent.name)
            }.toString(),
            waiting = "Doing ${intent.name}…",
            successPrefix = intent.name.lowercase().replaceFirstChar { it.uppercase() },
        )
        CommandIntent.StartAutonomy -> {
            controller.startAutonomous()
            CommandReply("Autonomy is on. I'll keep working in the background.")
        }
        CommandIntent.StopAutonomy -> {
            controller.stopAutonomous()
            CommandReply("Autonomy is off. Standing by.")
        }
        is CommandIntent.Echo -> enqueueAndAwait(
            agentId = EchoAgent.ID,
            title = "Voice: echo",
            payload = intent.message,
            waiting = "Echoing…",
            successPrefix = intent.message,
            useRawPayload = true,
        )
        is CommandIntent.Unknown -> CommandReply(
            spoken = if (intent.raw.isBlank()) {
                "I didn't catch that. Try again."
            } else {
                "I'm not sure what to do with that. You can ask me something, " +
                    "say read the screen, tap a button, or start autonomy."
            },
        )
    }

    private suspend fun askGrok(prompt: String): CommandReply {
        val settings = settingsRepository.current()
        if (!settings.hasApiKey) {
            return CommandReply(
                "No API key set. Add one in Settings, or use device commands like " +
                    "read the screen or start autonomy.",
            )
        }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    "You are Grokadile, a concise on-device voice assistant. " +
                        "Keep spoken replies short (1–3 sentences) unless asked for detail.",
                ),
                ChatMessage(ChatRole.USER, prompt),
            ),
            model = settings.grokModel,
            temperature = 0.7,
            maxTokens = 400,
        )
        return when (val result = grok.chat(request)) {
            is AppResult.Success -> {
                val content = result.data.content.trim()
                CommandReply(spoken = content.take(280), detail = content)
            }
            is AppResult.Failure -> {
                // Fall back to queued chat agent so the failure is visible in Tasks.
                val payload = buildJsonObject {
                    put("prompt", prompt)
                    put("model", settings.grokModel)
                }.toString()
                enqueueAndAwait(
                    agentId = GrokChatAgent.ID,
                    title = "Chat: ${prompt.take(40)}",
                    payload = payload,
                    waiting = "Asking Grok…",
                    successPrefix = "",
                    useRawPayload = true,
                )
            }
        }
    }

    private suspend fun enqueueAndAwait(
        agentId: String,
        title: String,
        payload: String,
        waiting: String,
        successPrefix: String,
        useRawPayload: Boolean = false,
        timeoutMs: Long = 45_000L,
    ): CommandReply {
        ensureEngineRunning()
        val task = Task(
            agentId = agentId,
            title = title,
            payload = if (useRawPayload) payload else payload,
            priority = TaskPriority.HIGH,
        )
        val id = controller.enqueue(task)
        logger.i(TAG, "Enqueued $agentId id=$id ($waiting)")

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = taskRepository.getById(id) ?: break
            when (current.status) {
                TaskStatus.SUCCEEDED -> {
                    val out = current.resultData?.trim().orEmpty()
                    val spoken = when {
                        successPrefix.isBlank() && out.isNotBlank() ->
                            out.take(280)
                        out.isBlank() ->
                            if (successPrefix.isBlank()) "Done." else "$successPrefix."
                        out.length <= 200 ->
                            if (successPrefix.isBlank()) out else "$successPrefix: $out"
                        else ->
                            if (successPrefix.isBlank()) {
                                out.take(200) + "…"
                            } else {
                                "$successPrefix. Check Tasks for the full result."
                            }
                    }
                    return CommandReply(spoken = spoken, detail = out.ifBlank { spoken }, taskId = id)
                }
                TaskStatus.FAILED, TaskStatus.CANCELLED -> {
                    val err = current.lastError ?: "failed"
                    return CommandReply(
                        spoken = "That didn't work: $err",
                        detail = err,
                        taskId = id,
                    )
                }
                else -> delay(250)
            }
        }
        return CommandReply(
            spoken = "Still working on it — I queued the task and will keep going.",
            detail = waiting,
            taskId = id,
        )
    }

    private suspend fun ensureEngineRunning() {
        if (!controller.engineState.value.running) {
            controller.startAutonomous()
            // Give the FGS a beat to start the loop before we claim work.
            delay(400)
        }
    }

    companion object {
        private const val TAG = "CommandDispatcher"
    }
}
