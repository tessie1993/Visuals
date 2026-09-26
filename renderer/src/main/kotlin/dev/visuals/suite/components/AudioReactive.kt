package dev.visuals.suite.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Bars that ease toward [levels], each in 0f..1f, such as normalized analyser frequency bins.
 * Draws nothing unless [modifier] gives it a size.
 */
@Composable
fun SpectrumBars(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animatedLevels = levels.map { level ->
        animateFloatAsState(level.coerceIn(0f, 1f), label = "bar").value
    }

    Canvas(modifier) {
        if (animatedLevels.isEmpty()) return@Canvas
        val slotWidth = size.width / animatedLevels.size
        val barWidth = slotWidth * 0.7f
        animatedLevels.forEachIndexed { index, level ->
            val barHeight = size.height * level
            drawRoundRect(
                color = color,
                topLeft = Offset(index * slotWidth + (slotWidth - barWidth) / 2, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/**
 * Ring that swells and brightens with [level], in 0f..1f, such as the input's RMS loudness.
 * Draws nothing unless [modifier] gives it a size.
 */
@Composable
fun LevelRing(
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val animatedLevel by animateFloatAsState(level.coerceIn(0f, 1f), label = "level")

    Canvas(modifier) {
        val strokeWidth = size.minDimension * 0.06f
        val maxRadius = size.minDimension / 2 - strokeWidth / 2
        val radius = maxRadius * (0.6f + 0.4f * animatedLevel)
        drawCircle(color = color.copy(alpha = 0.1f + 0.3f * animatedLevel), radius = radius)
        drawCircle(color = color, radius = radius, style = Stroke(width = strokeWidth))
    }
}
