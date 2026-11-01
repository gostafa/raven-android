package com.gostafa.raven.core.companion

import kotlin.math.roundToInt

data class CompanionPosition(val rightEdge: Boolean = true, val verticalFraction: Float = 0.5f)

internal fun companionCoordinates(width: Int, height: Int, bubbleSize: Int, position: CompanionPosition): Pair<Int, Int> {
    val x = if (position.rightEdge) (width - bubbleSize).coerceAtLeast(0) else 0
    val y = ((height - bubbleSize).coerceAtLeast(0) * position.verticalFraction.coerceIn(0f, 1f)).roundToInt()
    return x to y
}
