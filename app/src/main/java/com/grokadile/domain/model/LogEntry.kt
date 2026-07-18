package com.grokadile.domain.model

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/**
 * A structured log line. Persisted to Room so the in-app Logs screen can show
 * agent activity and so crash traces survive a process restart.
 */
data class LogEntry(
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val agentId: String? = null,
    val taskId: String? = null,
    val stackTrace: String? = null,
)
