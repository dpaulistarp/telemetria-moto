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
        rule.onNodeWithTag("dashboard").performScrollToNode(hasText("INICIAR VIAGEM"))
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("INICIAR VIAGEM") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("INICIAR VIAGEM").assertIsEnabled()
        rule.onNodeWithText("Histórico").performClick()
        rule.onNodeWithText("Suas viagens").assertIsDisplayed()
        rule.onNodeWithText("Ajustes").performClick()
        rule.onNodeWithText("Do seu jeito").assertIsDisplayed()
        rule.onNodeWithText("Painel").performClick()
        rule.onNodeWithTag("dashboard").performScrollToNode(hasText("Seu próximo caminho."))
        rule.onNodeWithText("Seu próximo caminho.").assertIsDisplayed()
    }
}
