package com.grokadile.di

import com.grokadile.agent.builtin.AppLaunchAgent
import com.grokadile.agent.builtin.ClipboardAgent
import com.grokadile.agent.builtin.DeviceHealthAgent
import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.agent.builtin.GrokChatAgent
import com.grokadile.agent.builtin.HeartbeatAgent
import com.grokadile.agent.builtin.NotificationListenerAgent
import com.grokadile.agent.builtin.PlannerAgent
import com.grokadile.agent.builtin.SchedulerAgent
import com.grokadile.agent.builtin.ScreenReadingAgent
import com.grokadile.agent.builtin.ScreenSummaryAgent
import com.grokadile.agent.builtin.SmartActionAgent
import com.grokadile.agent.builtin.ScreenTapAgent
import com.grokadile.agent.builtin.SwarmAgent
import com.grokadile.agent.builtin.VectorMemoryAgent
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AppCatalogProvider
import com.grokadile.domain.agent.ClipboardProvider
import com.grokadile.domain.agent.DeviceHealthProvider
import com.grokadile.domain.agent.NotificationContentProvider
import com.grokadile.domain.agent.ScreenActionProvider
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.service.LiveAppCatalogProvider
import com.grokadile.service.LiveClipboardProvider
import com.grokadile.service.LiveDeviceHealthProvider
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
    abstract fun bindScreenSummaryAgent(agent: ScreenSummaryAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindSmartActionAgent(agent: SmartActionAgent): Agent

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
    @IntoSet
    abstract fun bindVectorMemoryAgent(agent: VectorMemoryAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindPlannerAgent(agent: PlannerAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindClipboardAgent(agent: ClipboardAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindAppLaunchAgent(agent: AppLaunchAgent): Agent

    @Binds
    @IntoSet
    abstract fun bindDeviceHealthAgent(agent: DeviceHealthAgent): Agent

    @Binds
    abstract fun bindScreenContentProvider(impl: LiveScreenContentProvider): ScreenContentProvider

    @Binds
    abstract fun bindScreenActionProvider(impl: LiveScreenActionProvider): ScreenActionProvider

    @Binds
    abstract fun bindNotificationContentProvider(impl: LiveNotificationContentProvider): NotificationContentProvider

    @Binds
    abstract fun bindClipboardProvider(impl: LiveClipboardProvider): ClipboardProvider

    @Binds
    abstract fun bindAppCatalogProvider(impl: LiveAppCatalogProvider): AppCatalogProvider

    @Binds
    abstract fun bindDeviceHealthProvider(impl: LiveDeviceHealthProvider): DeviceHealthProvider
}
