package com.mototelemetry.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, TrackPointEntity::class, TripSegmentEntity::class,
    TelemetryEventEntity::class], version = 2, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class MotoDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
