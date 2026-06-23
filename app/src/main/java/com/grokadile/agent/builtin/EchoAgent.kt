package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal reference agent: echoes its payload and records it in memory. Handy
 * for smoke-testing the queue → engine → agent → result pipeline end to end.
 */
@Singleton
class EchoAgent @Inject constructor() : Agent {
    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Echo",
        description = "Echoes the task payload back. Used to verify the pipeline.",
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        context.logger.i("Echo: ${task.payload}")
        context.memory.put("last_echo", task.payload)
        return AgentResult.success(task.payload)
    }

    companion object {
        const val ID = "echo"
    }
}
