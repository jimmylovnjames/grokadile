package com.grokadile.core.logging

import com.grokadile.di.ApplicationScope
import com.grokadile.domain.model.LogEntry
import com.grokadile.domain.model.LogLevel
import com.grokadile.domain.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide structured logger. Every call fans out to Logcat (via Timber) for
 * development and to the Room-backed [LogRepository] for the in-app Logs
 * screen and post-mortem inspection. Persistence is fire-and-forget and can
 * never crash the caller.
 */
interface GrokLogger {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        agentId: String? = null,
        taskId: String? = null,
        throwable: Throwable? = null,
    )

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) =
        log(LogLevel.WARN, tag, message, throwable = t)
    fun e(tag: String, message: String, t: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable = t)
}

@Singleton
class GrokLoggerImpl @Inject constructor(
    private val logRepository: LogRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : GrokLogger {

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        agentId: String?,
        taskId: String?,
        throwable: Throwable?,
    ) {
        // Logcat (no-op in release where no Timber tree is planted).
        val priority = when (level) {
            LogLevel.VERBOSE -> android.util.Log.VERBOSE
            LogLevel.DEBUG -> android.util.Log.DEBUG
            LogLevel.INFO -> android.util.Log.INFO
            LogLevel.WARN -> android.util.Log.WARN
            LogLevel.ERROR -> android.util.Log.ERROR
        }
        Timber.tag(tag).log(priority, throwable, message)

        // Persist (best-effort; logging must never take the app down).
        scope.launch {
            runCatching {
                logRepository.append(
                    LogEntry(
                        level = level,
                        tag = tag,
                        message = message,
                        agentId = agentId,
                        taskId = taskId,
                        stackTrace = throwable?.stackTraceToString(),
                    ),
                )
            }
        }
    }
}
