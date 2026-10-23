package com.mototelemetry.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.mototelemetry.core.database.MotoDatabase
import com.mototelemetry.core.database.TripDao
import com.mototelemetry.core.datastore.PreferencesRepository
import com.mototelemetry.core.tracking.SessionRepository
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
    @Provides @Singleton fun sessions(dao: TripDao) = SessionRepository(dao)
}
