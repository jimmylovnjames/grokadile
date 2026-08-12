package com.grokadile.voice

import com.grokadile.core.logging.GrokLogger
import com.grokadile.di.ApplicationScope
import com.grokadile.domain.voice.CommandInterpreter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class VoicePhase {
    IDLE,
    LISTENING_WAKE,
    LISTENING_COMMAND,
    PROCESSING,
    SPEAKING,
}

data class VoiceUiState(
    val enabled: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    val partialTranscript: String = "",
    val lastError: String? = null,
    val rms: Float = 0f,
)

data class ChatMessageUi(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRoleUi,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val taskId: String? = null,
)

enum class ChatRoleUi { USER, ASSISTANT, SYSTEM }

/**
 * Siri-style voice loop: listen for "hey grok" → acknowledge → capture command →
 * dispatch → speak reply → return to wake listening.
 *
 * Also powers the dashboard chatbox (typed or push-to-talk).
 */
@Singleton
class VoiceAssistant @Inject constructor(
    private val speech: SpeechRecognizerClient,
    private val tts: TtsSpeaker,
    private val dispatcher: CommandDispatcher,
    private val logger: GrokLogger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private var commandJob: Job? = null
    private var wakeArmed = false

    init {
        speech.setListener(object : SpeechRecognizerClient.Listener {
            override fun onPartial(text: String, mode: ListenMode) {
                _state.update { it.copy(partialTranscript = text, lastError = null) }
                if (mode == ListenMode.WAKE && containsWakePhrase(text)) {
                    onWakeDetected(text)
                }
            }

            override fun onFinal(text: String, mode: ListenMode) {
                _state.update { it.copy(partialTranscript = text) }
                when (mode) {
                    ListenMode.WAKE -> {
                        if (containsWakePhrase(text)) onWakeDetected(text)
                        else if (_state.value.enabled) speech.start(ListenMode.WAKE)
                    }
                    ListenMode.COMMAND, ListenMode.PUSH_TO_TALK -> {
                        val command = stripWakePhrase(text)
                        if (command.isBlank()) {
                            scope.launch {
                                speak("Yes?")
                                if (_state.value.enabled) startCommandListen()
                            }
                        } else {
                            handleUtterance(command, fromVoice = true)
                        }
                    }
                }
            }

            override fun onListening(mode: ListenMode) {
                val phase = when (mode) {
                    ListenMode.WAKE -> VoicePhase.LISTENING_WAKE
                    ListenMode.COMMAND, ListenMode.PUSH_TO_TALK -> VoicePhase.LISTENING_COMMAND
                }
                _state.update {
                    it.copy(phase = phase, partialTranscript = "", lastError = null)
                }
            }

            override fun onError(message: String, willRetry: Boolean) {
                _state.update { it.copy(lastError = message) }
                if (!willRetry && !_state.value.enabled) {
                    _state.update { it.copy(phase = VoicePhase.IDLE) }
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                _state.update { it.copy(rms = rmsdB) }
            }
        })
    }

    fun isSpeechAvailable(): Boolean = speech.isAvailable()

    /** Toggle always-on wake-word listening (Siri-like). */
    fun setListeningEnabled(enabled: Boolean) {
        _state.update { it.copy(enabled = enabled, lastError = null) }
        if (enabled) {
            if (!speech.isAvailable()) {
                _state.update {
                    it.copy(
                        enabled = false,
                        lastError = "Speech recognition unavailable",
                        phase = VoicePhase.IDLE,
                    )
                }
                return
            }
            appendSystem("Listening for \"Hey Grok\"…")
            speech.start(ListenMode.WAKE)
        } else {
            speech.stop()
            tts.stop()
            commandJob?.cancel()
            wakeArmed = false
            _state.update { it.copy(phase = VoicePhase.IDLE, partialTranscript = "") }
            appendSystem("Voice listening off.")
        }
    }

    /** Push-to-talk from the chat mic — one command, then stop (unless wake mode on). */
    fun startPushToTalk() {
        if (!speech.isAvailable()) {
            _state.update { it.copy(lastError = "Speech recognition unavailable") }
            return
        }
        tts.stop()
        speech.start(ListenMode.PUSH_TO_TALK)
    }

    fun stopPushToTalk() {
        if (_state.value.enabled) {
            speech.start(ListenMode.WAKE)
        } else {
            speech.stop()
            _state.update { it.copy(phase = VoicePhase.IDLE) }
        }
    }

    /** Typed chatbox submission. */
    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        handleUtterance(trimmed, fromVoice = false)
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    private fun onWakeDetected(raw: String) {
        if (wakeArmed) return
        wakeArmed = true
        speech.stop()
        logger.i(TAG, "Wake phrase in \"$raw\"")
        scope.launch {
            _events.tryEmit(VoiceEvent.WakeDetected)
            appendSystem("Heard you — listening…")
            speak("Yes?")
            wakeArmed = false
            startCommandListen()
        }
    }

    private fun startCommandListen() {
        speech.start(ListenMode.COMMAND)
    }

    private fun handleUtterance(text: String, fromVoice: Boolean) {
        commandJob?.cancel()
        appendUser(text)
        _state.update { it.copy(phase = VoicePhase.PROCESSING, partialTranscript = "") }
        commandJob = scope.launch {
            val reply = runCatching { dispatcher.dispatch(text) }
                .getOrElse {
                    logger.e(TAG, "Dispatch failed", it)
                    CommandReply("Something went wrong: ${it.message ?: "error"}")
                }
            appendAssistant(reply.detail, reply.taskId)
            if (fromVoice || _state.value.enabled) {
                speak(reply.spoken)
            }
            if (_state.value.enabled) {
                speech.start(ListenMode.WAKE)
            } else {
                _state.update { it.copy(phase = VoicePhase.IDLE) }
            }
        }
    }

    private suspend fun speak(text: String) {
        _state.update { it.copy(phase = VoicePhase.SPEAKING) }
        speech.stop()
        tts.speakAndWait(text)
    }

    private fun appendUser(text: String) {
        _messages.update { it + ChatMessageUi(role = ChatRoleUi.USER, text = text) }
    }

    private fun appendAssistant(text: String, taskId: String?) {
        _messages.update {
            it + ChatMessageUi(role = ChatRoleUi.ASSISTANT, text = text, taskId = taskId)
        }
    }

    private fun appendSystem(text: String) {
        _messages.update { it + ChatMessageUi(role = ChatRoleUi.SYSTEM, text = text) }
    }

    companion object {
        private const val TAG = "VoiceAssistant"

        fun containsWakePhrase(text: String): Boolean {
            val lower = text.lowercase().trim()
            return CommandInterpreter.WAKE_PHRASES.any { phrase ->
                lower == phrase ||
                    lower.startsWith("$phrase ") ||
                    lower.startsWith("$phrase,") ||
                    lower.contains(" $phrase")
            }
        }

        fun stripWakePhrase(text: String): String {
            var s = text.trim()
            for (phrase in CommandInterpreter.WAKE_PHRASES.sortedByDescending { it.length }) {
                if (s.startsWith(phrase, ignoreCase = true)) {
                    s = s.substring(phrase.length).trimStart(',', ' ', '?', '!')
                    break
                }
            }
            return s.trim()
        }
    }
}

sealed class VoiceEvent {
    data object WakeDetected : VoiceEvent()
}
