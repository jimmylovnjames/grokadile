package com.grokadile.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class ListenMode {
    /** Continuous listening for wake phrases like "hey grok". */
    WAKE,

    /** Capture the next user utterance as a command. */
    COMMAND,

    /** One-shot push-to-talk from the chat mic button. */
    PUSH_TO_TALK,
}

/**
 * Main-thread [SpeechRecognizer] client. Callers receive callbacks via [Listener].
 * Recognition restarts automatically in [ListenMode.WAKE] after benign errors.
 */
@Singleton
class SpeechRecognizerClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    interface Listener {
        fun onPartial(text: String, mode: ListenMode)
        fun onFinal(text: String, mode: ListenMode)
        fun onListening(mode: ListenMode)
        fun onError(message: String, willRetry: Boolean)
        fun onRmsChanged(rmsdB: Float) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var mode: ListenMode = ListenMode.WAKE
    private val running = AtomicBoolean(false)
    private val restarting = AtomicBoolean(false)

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start(mode: ListenMode) {
        this.mode = mode
        running.set(true)
        main.post { startInternal() }
    }

    fun stop() {
        running.set(false)
        main.post {
            recognizer?.stopListening()
            recognizer?.cancel()
            destroyRecognizer()
        }
    }

    fun currentMode(): ListenMode = mode

    private fun startInternal() {
        if (!running.get()) return
        if (!isAvailable()) {
            listener?.onError("Speech recognition is not available on this device", willRetry = false)
            return
        }
        ensureRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            when (mode) {
                ListenMode.WAKE -> {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                }
                ListenMode.COMMAND, ListenMode.PUSH_TO_TALK -> {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                }
            }
        }
        try {
            recognizer?.startListening(intent)
            listener?.onListening(mode)
        } catch (t: Throwable) {
            listener?.onError(t.message ?: "Failed to start listening", willRetry = false)
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    listener?.onRmsChanged(rmsdB)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    val retry = running.get() && mode == ListenMode.WAKE &&
                        error in RETRYABLE_ERRORS
                    val message = errorMessage(error)
                    listener?.onError(message, willRetry = retry)
                    if (retry) scheduleRestart(delayMs = 400L)
                    else if (running.get() && mode == ListenMode.WAKE) {
                        scheduleRestart(delayMs = 800L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotBlank()) {
                        listener?.onFinal(text, mode)
                    } else if (running.get() && mode == ListenMode.WAKE) {
                        scheduleRestart(delayMs = 300L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotBlank()) listener?.onPartial(text, mode)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running.get()) return
        if (!restarting.compareAndSet(false, true)) return
        main.postDelayed({
            restarting.set(false)
            if (running.get()) startInternal()
        }, delayMs)
    }

    private fun destroyRecognizer() {
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
        SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        else -> "Speech error ($code)"
    }

    companion object {
        private val RETRYABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
        )
    }
}
