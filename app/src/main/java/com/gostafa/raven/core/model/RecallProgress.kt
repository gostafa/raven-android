package com.gostafa.raven.core.model

import kotlin.math.roundToInt

/** Counts contain only manual ratings in the inclusive range 1..4. */
data class PartRecall(
    val partId: String,
    val cardId: String,
    val folderId: String,
    val rememberThis: Boolean,
    val reviewCount: Int,
    val successCount: Int,
)

/** Matches Raven's cardProgress: equal part weighting, rounded only after averaging. */
fun cardRecallPercent(parts: List<PartRecall>): Int {
    val reviewable = parts.filter { it.rememberThis }
    if (reviewable.isEmpty()) return 0
    val total = reviewable.sumOf { part ->
        if (part.reviewCount == 0) 0.0 else part.successCount.toDouble() / part.reviewCount
    }
    return (total / reviewable.size * 100).roundToInt().coerceIn(0, 100)
}
