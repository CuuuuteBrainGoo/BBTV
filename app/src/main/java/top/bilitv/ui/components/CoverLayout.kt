package top.bilitv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** A short window can cap media height without squeezing the title/author underneath. */
internal fun Modifier.cappedCover(aspectRatio: Float, heightLimit: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    } else {
        val height = constraints.constrainHeight(minOf(
            (constraints.maxWidth / aspectRatio).roundToInt(), heightLimit.roundToPx()).coerceAtLeast(1))
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}
