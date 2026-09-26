package dev.visuals.suite.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Shows [page], sliding forward when it increases and back when it decreases. */
@Composable
fun PageTransition(
    page: Int,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally { width -> direction * width } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> -direction * width } + fadeOut())
        },
        label = "page",
    ) { targetPage ->
        content(targetPage)
    }
}
