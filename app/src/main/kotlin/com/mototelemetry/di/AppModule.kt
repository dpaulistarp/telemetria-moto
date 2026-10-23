package com.mototelemetry.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.mototelemetry.core.database.MotoDatabase
import com.mototelemetry.core.database.TripDao
import com.mototelemetry.core.datastore.PreferencesRepository
import com.mototelemetry.core.tracking.SessionRepository
import com.mototelemetry.core.tracking.AndroidLocationSource
import com.mototelemetry.core.tracking.AndroidTelemetryClock
import com.mototelemetry.core.telemetry.LocationSource
import com.mototelemetry.core.telemetry.TelemetryClock
import com.mototelemetry.core.telemetry.SpeedometerMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.riderDataStore by preferencesDataStore(name = "rider_preferences")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MotoDatabase =
        Room.databaseBuilder(context, MotoDatabase::class.java, "moto-telemetry.db").build()
    @Provides fun tripDao(db: MotoDatabase): TripDao = db.tripDao()
    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context) = PreferencesRepository(context.riderDataStore)
    @Provides @Singleton fun locationSource(@ApplicationContext context: Context): LocationSource = AndroidLocationSource(context)
    @Provides fun telemetryClock(): TelemetryClock = AndroidTelemetryClock
    @Provides @Singleton fun speedometer(source: LocationSource, clock: TelemetryClock) = SpeedometerMonitor(source, clock)
    @Provides @Singleton fun sessions(dao: TripDao) = SessionRepository(dao)
}
