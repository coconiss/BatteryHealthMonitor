// di/DatabaseModule.kt
package com.batteryhealth.monitor.di

import android.content.Context
import androidx.room.Room
import com.batteryhealth.monitor.data.local.AppDatabase
import com.batteryhealth.monitor.data.local.dao.BatteryMeasurementDao
import com.batteryhealth.monitor.data.local.dao.ChargingSessionDao
import com.batteryhealth.monitor.data.local.dao.DeviceBatterySpecDao
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
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "battery_health_db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideChargingSessionDao(database: AppDatabase): ChargingSessionDao {
        return database.chargingSessionDao()
    }

    @Provides
    fun provideDeviceBatterySpecDao(database: AppDatabase): DeviceBatterySpecDao {
        return database.deviceBatterySpecDao()
    }

    @Provides
    fun provideBatteryMeasurementDao(database: AppDatabase): BatteryMeasurementDao {
        return database.batteryMeasurementDao()
    }
}