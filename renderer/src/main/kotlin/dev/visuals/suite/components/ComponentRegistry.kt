package dev.visuals.suite.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

val componentRegistry: Map<String, @Composable () -> Unit> = mapOf(
    "WelcomeCard" to { WelcomeCard() },
    "ListenButton" to {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListenButton(listening = false, onClick = {})
            ListenButton(listening = true, onClick = {})
        }
    },
    "ExpandableCard" to {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpandableCard(title = "Input", expanded = true, onToggle = {}) {
                Text(text = "Microphone, FFT size 256", style = MaterialTheme.typography.bodyMedium)
            }
            ExpandableCard(title = "Colors", expanded = false, onToggle = {}) {}
        }
    },
    "RevealPanel" to {
        RevealPanel(visible = true) {
            Text(text = "Music Visualizer", style = MaterialTheme.typography.titleLarge)
            Text(text = "Microphone input drives the visualization.", style = MaterialTheme.typography.bodyMedium)
        }
    },
    "SpectrumBars" to {
        SpectrumBars(levels = sampleLevels, modifier = Modifier.size(width = 320.dp, height = 120.dp))
    },
    "LevelRing" to { LevelRing(level = 0.7f, modifier = Modifier.size(120.dp)) },
    "DotsLoader" to { DotsLoader() },
    "SkeletonBlock" to {
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(20.dp))
            SkeletonBlock(Modifier.fillMaxWidth().height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(14.dp))
        }
    },
    "PageTransition" to {
        PageTransition(page = 1) { page ->
            Text(text = "Step ${page + 1} of 3", style = MaterialTheme.typography.titleMedium)
        }
    },
)

/** 32 deterministic bar heights, loud in the lows and tapering toward the highs. */
private val sampleLevels = List(32) { index -> (1 - index / 32f) * (0.4f + 0.6f * abs(sin(index * 0.7f))) }
