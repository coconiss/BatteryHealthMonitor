// di/AppModule.kt
package com.batteryhealth.monitor.di

import android.content.Context
import com.batteryhealth.monitor.data.remote.BatterySpecService
import com.batteryhealth.monitor.data.remote.GsmArenaScraperService
import com.batteryhealth.monitor.util.NotificationHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNotificationHelper(
        @ApplicationContext context: Context
    ): NotificationHelper {
        return NotificationHelper(context)
    }

    @Provides
    @Singleton
    fun provideGsmArenaScraperService(): GsmArenaScraperService {
        return GsmArenaScraperService()
    }
}