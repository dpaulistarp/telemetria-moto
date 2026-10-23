package com.mototelemetry

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun dashboardHistoryAndSettingsAreReachable() {
        rule.onNodeWithText("INICIAR VIAGEM").assertIsNotEnabled()
        rule.onNodeWithText("Histórico").performClick()
        rule.onNodeWithText("Suas viagens").assertIsDisplayed()
        rule.onNodeWithText("Ajustes").performClick()
        rule.onNodeWithText("Do seu jeito").assertIsDisplayed()
        rule.onNodeWithText("Painel").performClick()
        rule.onNodeWithText("Seu próximo caminho.").assertIsDisplayed()
    }
}
