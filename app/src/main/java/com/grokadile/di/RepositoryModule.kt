package com.grokadile.di

import com.grokadile.agent.runtime.DefaultAgentRegistry
import com.grokadile.core.logging.GrokLogger
import com.grokadile.core.logging.GrokLoggerImpl
import com.grokadile.data.repository.AgentMemoryRepositoryImpl
import com.grokadile.data.repository.GrokRepositoryImpl
import com.grokadile.data.repository.LogRepositoryImpl
import com.grokadile.data.repository.SettingsRepositoryImpl
import com.grokadile.data.repository.TaskRepositoryImpl
import com.grokadile.domain.agent.AgentRegistry
import com.grokadile.domain.repository.AgentMemoryRepository
import com.grokadile.domain.repository.GrokRepository
import com.grokadile.domain.repository.LogRepository
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(impl: LogRepositoryImpl): LogRepository

    @Binds
    @Singleton
    abstract fun bindAgentMemoryRepository(impl: AgentMemoryRepositoryImpl): AgentMemoryRepository

    @Binds
    @Singleton
    abstract fun bindGrokRepository(impl: GrokRepositoryImpl): GrokRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindGrokLogger(impl: GrokLoggerImpl): GrokLogger

    @Binds
    @Singleton
    abstract fun bindAgentRegistry(impl: DefaultAgentRegistry): AgentRegistry
}
