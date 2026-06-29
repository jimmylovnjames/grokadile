package com.grokadile.di

import com.grokadile.domain.screen.ScreenController
import com.grokadile.service.accessibility.AccessibilityScreenController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScreenModule {

    /** Agents inject [ScreenController]; the service injects the concrete type
     *  to attach/detach — both resolve to the same singleton. */
    @Binds
    @Singleton
    abstract fun bindScreenController(impl: AccessibilityScreenController): ScreenController
}
