package com.grokadile.di

import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.agent.builtin.GrokChatAgent
import com.grokadile.agent.builtin.HeartbeatAgent
import com.grokadile.domain.agent.Agent
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Contributes the built-in agents into the multibound `Set<Agent>` consumed by
 * [com.grokadile.agent.runtime.DefaultAgentRegistry]. Register a new agent by
 * adding one `@Binds @IntoSet` line here — nothing else needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {

    @Binds
    @IntoSet
    abstract fun bindEchoAgent(agent: EchoAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindGrokChatAgent(agent: GrokChatAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindHeartbeatAgent(agent: HeartbeatAgent): Agent
}
