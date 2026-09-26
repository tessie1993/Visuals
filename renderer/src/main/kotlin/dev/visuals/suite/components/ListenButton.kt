package dev.visuals.suite.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Start/stop toggle that shrinks while pressed and animates its colors and label between states. */
@Composable
fun ListenButton(
    listening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "scale")
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        if (listening) colors.error else colors.primary,
        label = "containerColor",
    )
    val contentColor by animateColorAsState(
        if (listening) colors.onError else colors.onPrimary,
        label = "contentColor",
    )

    Button(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        interactionSource = interactionSource,
    ) {
        AnimatedContent(targetState = listening, label = "label") { isListening ->
            Text(text = if (isListening) "Stop" else "Start Listening")
        }
    }
}
