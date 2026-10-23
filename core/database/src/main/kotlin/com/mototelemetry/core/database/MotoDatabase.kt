package com.mototelemetry.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, TrackPointEntity::class, TripSegmentEntity::class,
    TelemetryEventEntity::class], version = 1, exportSchema = true)
abstract class MotoDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
