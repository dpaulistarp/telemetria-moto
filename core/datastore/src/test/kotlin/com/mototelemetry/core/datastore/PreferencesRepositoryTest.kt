package com.mototelemetry.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun defaultsDoNotInventCalibration() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("defaults.preferences_pb") }
        assertEquals(RiderPreferences(), PreferencesRepository(store).preferences.first())
    }
    @Test fun persistsIndependentPreferences() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("settings.preferences_pb") }
        val repo = PreferencesRepository(store)
        repo.setMountMode(MountMode.POCKET)
        repo.setSmoothing(false)
        repo.setKeepScreenOn(true)
        repo.setThresholds(3f, 4f)
        val loaded = PreferencesRepository(store).preferences.first()
        assertEquals(RiderPreferences(MountMode.POCKET, false, true, 3f, 4f), loaded)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteThreshold() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("invalid.preferences_pb") }
        PreferencesRepository(store).setThresholds(Float.NaN, 3.5f)
    }
}
