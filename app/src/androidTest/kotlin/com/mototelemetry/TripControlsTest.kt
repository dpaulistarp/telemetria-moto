package com.mototelemetry

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mototelemetry.core.database.TripEntity
import com.mototelemetry.core.tracking.TrackingRuntime
import com.mototelemetry.ui.MotoTheme
import com.mototelemetry.ui.TripControls
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripControlsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun finishingRequiresConfirmationAndTargetsTheRightTrip() {
        var finished: String? = null
        rule.setContent { MotoTheme {
            TripControls(TripEntity("trip", 1000, status = "PAUSED"), true, TrackingRuntime(serviceRunning = true),
                {}, {}, {}, { finished = it }, {}, {})
        } }
        rule.onNodeWithText("RETOMAR VIAGEM").assertIsEnabled()
        rule.onNodeWithText("FINALIZAR VIAGEM").performClick()
        rule.runOnIdle { assertNull(finished) }
        rule.onNodeWithText("Finalizar e salvar").performClick()
        rule.runOnIdle { assertEquals("trip", finished) }
    }
    @Test fun interruptedTripOffersRecoveryWithoutPretendingItIsRecording() {
        rule.setContent { MotoTheme {
            TripControls(TripEntity("trip", 1000, status = "INTERRUPTED"), true, TrackingRuntime(),
                {}, {}, {}, {}, {}, {})
        } }
        rule.onNodeWithText("RECUPERAR VIAGEM").assertIsEnabled()
        rule.onNodeWithText("PAUSAR VIAGEM").assertDoesNotExist()
    }
}
