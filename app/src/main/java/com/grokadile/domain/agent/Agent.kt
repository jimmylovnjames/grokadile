package com.grokadile.domain.agent

import com.grokadile.domain.model.Task

/**
 * The plugin contract. A new capability for Grokadile is a new [Agent]:
 * declare a [descriptor], implement [execute], and register it (typically by
 * `@Binds @IntoSet` in [com.grokadile.di.AgentModule]).
 *
 * Implementations must be stateless across tasks — durable state belongs in
 * [AgentContext.memory]. [execute] runs on a background dispatcher inside a
 * cancellable coroutine; honor [AgentContext.isActive] for long work.
 */
interface Agent {
    val descriptor: AgentDescriptor

    /** Convenience: the stable id this agent is keyed by in the registry. */
    val id: String get() = descriptor.id

    /** Perform the unit of work described by [task]. Must not throw for
     *  expected failures — return [AgentResult.Failure]/[AgentResult.Retry]. */
    suspend fun execute(task: Task, context: AgentContext): AgentResult

    /** Optional one-time hook when the registry first loads this agent. */
    suspend fun onRegistered() {}
}
