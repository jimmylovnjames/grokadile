package com.grokadile.core.logging

import com.grokadile.domain.model.LogEntry
import com.grokadile.domain.model.LogLevel
import com.grokadile.domain.repository.LogRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures otherwise-fatal uncaught exceptions, persists them synchronously so
 * the trace survives the process death, then delegates to the platform handler
 * so the OS still shows/handles the crash normally.
 */
@Singleton
class CrashHandler @Inject constructor(
    private val logRepository: LogRepository,
) : Thread.UncaughtExceptionHandler {

    private var delegate: Thread.UncaughtExceptionHandler? = null

    fun install() {
        if (delegate == null) {
            delegate = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(this)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            // Blocking + bounded: we must finish writing before the VM dies.
            runBlocking {
                withTimeoutOrNull(1500) {
                    logRepository.append(
                        LogEntry(
                            level = LogLevel.ERROR,
                            tag = "CRASH",
                            message = "Uncaught exception on '${thread.name}': ${throwable.message}",
                            stackTrace = throwable.stackTraceToString(),
                        ),
                    )
                }
            }
        }
        delegate?.uncaughtException(thread, throwable)
    }
}
