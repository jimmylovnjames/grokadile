package com.grokadile.di

import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.agent.builtin.GrokChatAgent
import com.grokadile.agent.builtin.HeartbeatAgent
import com.grokadile.agent.builtin.NotificationListenerAgent
import com.grokadile.agent.builtin.SchedulerAgent
import com.grokadile.agent.builtin.ScreenReadingAgent
import com.grokadile.agent.builtin.ScreenTapAgent
import com.grokadile.agent.builtin.SwarmAgent
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.NotificationContentProvider
import com.grokadile.domain.agent.ScreenActionProvider
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.service.LiveNotificationContentProvider
import com.grokadile.service.LiveScreenActionProvider
import com.grokadile.service.LiveScreenContentProvider
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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

    @Binds
    @IntoSet
    abstract fun bindScreenReadingAgent(agent: ScreenReadingAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindScreenTapAgent(agent: ScreenTapAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindSchedulerAgent(agent: SchedulerAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindNotificationListenerAgent(agent: NotificationListenerAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindSwarmAgent(agent: SwarmAgent): Agent

    @Binds
    abstract fun bindScreenContentProvider(impl: LiveScreenContentProvider): ScreenContentProvider

    @Binds
    abstract fun bindScreenActionProvider(impl: LiveScreenActionProvider): ScreenActionProvider

    @Binds
    abstract fun bindNotificationContentProvider(impl: LiveNotificationContentProvider): NotificationContentProvider
}
