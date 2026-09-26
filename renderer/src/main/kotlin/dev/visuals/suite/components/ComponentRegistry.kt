package dev.visuals.suite.components

import androidx.compose.runtime.Composable

val componentRegistry: Map<String, @Composable () -> Unit> = mapOf(
    "WelcomeCard" to { WelcomeCard() },
)
