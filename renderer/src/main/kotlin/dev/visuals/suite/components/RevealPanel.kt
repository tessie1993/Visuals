package dev.visuals.suite.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Card that rises and fades in when [visible] turns true, including on first composition, and sinks out when false. */
@Composable
fun RevealPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    SideEffect { visibleState.targetState = visible }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { height -> height / 4 } + scaleIn(initialScale = 0.95f),
        exit = fadeOut() + slideOutVertically { height -> height / 4 } + scaleOut(targetScale = 0.95f),
    ) {
        Card {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}
