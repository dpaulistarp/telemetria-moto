package com.mototelemetry.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MotoDatabase::class.java)
    @Test fun migrationOneToTwoPreservesTripAndTrackPoint() {
        val database = helper.createDatabase("migration-test", 1)
        database.execSQL("""INSERT INTO trips (id,startedAtUtcMs,status,distanceMeters,movingTimeMs,elapsedTimeMs,
            pausedTimeMs,maxSpeedMps,averageSpeedMps,totalAverageSpeedMps,totalPoints,lastUpdatedAtUtcMs,algorithmVersion)
            VALUES ('old',1000,'COMPLETED',123.0,1000,2000,0,10.0,5.0,4.0,1,3000,1)""")
        database.execSQL("INSERT INTO trip_segments (id,tripId,startedAtUtcMs,endedAtUtcMs,reason) VALUES ('s','old',1000,3000,'RECORDING')")
        database.execSQL("""INSERT INTO track_points (tripId,sequence,timestampUtcMs,elapsedRealtimeNanos,latitude,longitude,
            horizontalAccuracyMeters,accepted,segmentId) VALUES ('old',0,2000,2000000000,-23.5,-46.6,3.0,1,'s')""")
        database.close()
        val migrated = helper.runMigrationsAndValidate("migration-test", 2, true)
        migrated.query("SELECT distanceMeters FROM trips WHERE id='old'").use { assertTrue(it.moveToFirst()); assertEquals(123.0,it.getDouble(0),0.0) }
        migrated.query("SELECT isMock,latitude FROM track_points WHERE tripId='old'").use {
            assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)); assertEquals(-23.5,it.getDouble(1),0.0)
        }
        migrated.close()
    }
}
