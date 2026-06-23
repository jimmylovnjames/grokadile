package com.grokadile.domain.agent

/**
 * Outcome of a single [Agent.execute]. The orchestrator maps these onto task
 * status transitions (success / retry-with-backoff / terminal failure).
 */
sealed interface AgentResult {
    /** Work completed; [output] is an optional JSON/string result for the task. */
    data class Success(val output: String? = null) : AgentResult

    /** Transient failure — re-queue with backoff (unless attempts are exhausted). */
    data class Retry(val reason: String, val backoffMillis: Long? = null) : AgentResult

    /** Permanent failure — do not retry. */
    data class Failure(val reason: String, val cause: Throwable? = null) : AgentResult

    companion object {
        fun success(output: String? = null): AgentResult = Success(output)
        fun retry(reason: String, backoffMillis: Long? = null): AgentResult =
            Retry(reason, backoffMillis)
        fun failure(reason: String, cause: Throwable? = null): AgentResult =
            Failure(reason, cause)
    }
}
