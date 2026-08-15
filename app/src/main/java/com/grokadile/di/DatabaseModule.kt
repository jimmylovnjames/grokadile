package com.grokadile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.grokadile.data.local.GrokadileDatabase
import com.grokadile.data.local.dao.AgentMemoryDao
import com.grokadile.data.local.dao.LogDao
import com.grokadile.data.local.dao.TaskDao
import com.grokadile.data.local.dao.VectorMemoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GrokadileDatabase =
        Room.databaseBuilder(context, GrokadileDatabase::class.java, GrokadileDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTaskDao(db: GrokadileDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideLogDao(db: GrokadileDatabase): LogDao = db.logDao()

    @Provides
    fun provideAgentMemoryDao(db: GrokadileDatabase): AgentMemoryDao = db.agentMemoryDao()

    @Provides
    fun provideVectorMemoryDao(db: GrokadileDatabase): VectorMemoryDao = db.vectorMemoryDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("grokadile_settings")
    }
}
