package com.grokadile.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Thin wrapper around [TextToSpeech] for Siri-like spoken replies. */
@Singleton
class TtsSpeaker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready.set(status == TextToSpeech.SUCCESS)
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.05f)
            }
        }
    }

    fun isReady(): Boolean = ready.get()

    fun speak(text: String, flush: Boolean = true) {
        val engine = tts ?: return
        if (!ready.get() || text.isBlank()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, UUID.randomUUID().toString())
    }

    suspend fun speakAndWait(text: String) {
        val engine = tts ?: return
        if (!ready.get() || text.isBlank()) return
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { engine.stop() }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }
}
