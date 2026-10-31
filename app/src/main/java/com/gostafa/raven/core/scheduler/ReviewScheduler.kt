package com.gostafa.raven.core.scheduler

import com.gostafa.raven.core.model.FsrsState
import com.gostafa.raven.core.model.Rating
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

interface ReviewScheduler {
    val version: String
    fun schedule(state: FsrsState, rating: Rating, now: Long): FsrsState
    fun retrievability(state: FsrsState, now: Long): Double
}

/** FSRS-6 scheduler adapter. Defaults and update equations follow the FSRS-6 specification. */
class Fsrs6Scheduler : ReviewScheduler {
    override val version: String = "FSRS-6"

    private val weights = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )
    private val decay = -weights[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    override fun schedule(state: FsrsState, rating: Rating, now: Long): FsrsState {
        val grade = rating.value
        val nextReps = state.repetitions + 1
        val elapsedDays = state.lastReviewAt?.let { ((now - it).coerceAtLeast(0L) / DAY_MS).toInt() } ?: 0
        val memory = if (state.status == "New" || state.stability <= 0.0) {
            Memory(initialStability(grade), initialDifficulty(grade))
        } else {
            val currentRetrievability = retrievability(state, now)
            val newDifficulty = nextDifficulty(state.difficulty, grade)
            val newStability = if (elapsedDays < 1) {
                nextShortTermStability(state.stability, grade)
            } else if (grade == Rating.AGAIN.value) {
                nextForgetStability(state.difficulty, state.stability, currentRetrievability)
            } else {
                nextRecallStability(state.difficulty, state.stability, currentRetrievability, grade)
            }
            Memory(newStability, newDifficulty)
        }

        val isNew = state.status == "New" || state.stability <= 0.0
        val dueAt: Long
        val status: String
        val scheduledDays: Int
        val step: Int
        val lapses: Int

        when {
            grade == Rating.AGAIN.value -> {
                dueAt = now + if (isNew) MINUTE_MS else TEN_MINUTES_MS
                status = if (isNew) "Learning" else "Relearning"
                scheduledDays = 0
                step = 0
                lapses = state.lapses + if (isNew) 0 else 1
            }
            grade == Rating.HARD.value && isNew -> {
                dueAt = now + FIVE_MINUTES_MS
                status = "Learning"
                scheduledDays = 0
                step = 0
                lapses = state.lapses
            }
            grade == Rating.GOOD.value && isNew -> {
                dueAt = now + TEN_MINUTES_MS
                status = "Learning"
                scheduledDays = 0
                step = 0
                lapses = state.lapses
            }
            grade == Rating.HARD.value && state.status in setOf("Learning", "Relearning") -> {
                dueAt = now + FIVE_MINUTES_MS
                status = state.status
                scheduledDays = 0
                step = state.learningStep
                lapses = state.lapses
            }
            else -> {
                val interval = nextInterval(memory.stability)
                dueAt = now + interval * DAY_MS
                status = "Review"
                scheduledDays = interval
                step = 0
                lapses = state.lapses
            }
        }

        return FsrsState(
            version = 6,
            status = status,
            stability = memory.stability,
            difficulty = memory.difficulty,
            dueAt = dueAt,
            lastReviewAt = now,
            scheduledDays = scheduledDays,
            elapsedDays = elapsedDays,
            repetitions = nextReps,
            lapses = lapses,
            learningStep = step,
        )
    }

    override fun retrievability(state: FsrsState, now: Long): Double {
        if (state.stability <= 0.0 || state.lastReviewAt == null) return 0.0
        val elapsed = ((now - state.lastReviewAt).coerceAtLeast(0L)).toDouble() / DAY_MS
        return (1.0 + factor * elapsed / state.stability).pow(decay)
    }

    private fun initialDifficulty(grade: Int): Double =
        (weights[4] - exp(weights[5] * (grade - 1)) + 1.0).coerceIn(1.0, 10.0)

    private fun initialStability(grade: Int): Double = weights[grade - 1].coerceIn(0.1, 36_500.0)

    private fun nextDifficulty(current: Double, grade: Int): Double {
        val delta = -weights[6] * (grade - 3)
        val damped = delta * (10.0 - current) / 9.0
        val moved = current + damped
        val easyDifficulty = initialDifficulty(Rating.EASY.value)
        return (weights[7] * easyDifficulty + (1.0 - weights[7]) * moved).coerceIn(1.0, 10.0)
    }

    private fun nextShortTermStability(current: Double, grade: Int): Double {
        var increase = exp(weights[17] * (grade - 3 + weights[18])) * current.pow(-weights[19])
        if (grade >= Rating.GOOD.value) increase = maxOf(increase, 1.0)
        return (current * increase).coerceIn(0.001, 36_500.0)
    }

    private fun nextForgetStability(difficulty: Double, stability: Double, recall: Double): Double {
        val minimum = stability / exp(weights[17] * weights[18])
        val value = weights[11] * difficulty.pow(-weights[12]) *
            ((stability + 1.0).pow(weights[13]) - 1.0) * exp((1.0 - recall) * weights[14])
        return minOf(value, minimum).coerceIn(0.001, 36_500.0)
    }

    private fun nextRecallStability(difficulty: Double, stability: Double, recall: Double, grade: Int): Double {
        val hard = if (grade == Rating.HARD.value) weights[15] else 1.0
        val easy = if (grade == Rating.EASY.value) weights[16] else 1.0
        val increase = exp(weights[8]) * (11.0 - difficulty) * stability.pow(-weights[9]) *
            (exp((1.0 - recall) * weights[10]) - 1.0) * hard * easy
        return (stability * (1.0 + increase)).coerceIn(0.001, 36_500.0)
    }

    private fun nextInterval(stability: Double): Int =
        (stability / factor * (0.9.pow(1.0 / decay) - 1.0)).roundToInt().coerceIn(1, 36_500)

    private data class Memory(val stability: Double, val difficulty: Double)

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTES_MS = 5 * MINUTE_MS
        const val TEN_MINUTES_MS = 10 * MINUTE_MS
        const val DAY_MS = 24 * 60 * MINUTE_MS
    }
}

