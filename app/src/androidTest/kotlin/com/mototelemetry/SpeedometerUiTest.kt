package com.mototelemetry

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mototelemetry.core.telemetry.*
import com.mototelemetry.ui.MotoTheme
import com.mototelemetry.ui.SpeedometerReadout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedometerUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun staleSignalRemovesPreviousSpeed() {
        val state = mutableStateOf(SpeedometerState(status = SpeedometerStatus.GOOD, displaySpeedMps = 25.0))
        rule.setContent { MotoTheme { SpeedometerReadout(state.value) } }
        rule.onNodeWithTag("live-speed").assertTextEquals("90")
        rule.runOnIdle { state.value = SpeedometerState(status = SpeedometerStatus.STALE) }
        rule.onNodeWithTag("live-speed").assertTextEquals("—")
        rule.onNodeWithText("GPS INDISPONÍVEL").assertIsDisplayed()
    }
    @Test fun simulatedZeroIsExplicitAndNotConfusedWithMissingSpeed() {
        rule.setContent { MotoTheme { SpeedometerReadout(SpeedometerState(
            status = SpeedometerStatus.GOOD, displaySpeedMps = 0.0, isMock = true)) } }
        rule.onNodeWithTag("live-speed").assertTextEquals("0")
        rule.onNodeWithText("LOCALIZAÇÃO SIMULADA").assertIsDisplayed()
    }
}
