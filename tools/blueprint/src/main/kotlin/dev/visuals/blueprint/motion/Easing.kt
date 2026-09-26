package dev.visuals.blueprint.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** CSS / Compose `CubicBezierEasing`: a curve through (0,0), (x1,y1), (x2,y2), (1,1). */
data class CubicBezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {

    /** Eased progress at time fraction [u] in [0, 1]. */
    fun valueAt(u: Double): Double {
        if (u <= 0.0) return 0.0
        if (u >= 1.0) return 1.0
        return bezier(solveCurveX(u), y1, y2)
    }

    /** Finds s with x(s) = u: Newton steps, then bisection (x is monotonic for x1, x2 in [0, 1]). */
    private fun solveCurveX(u: Double): Double {
        var s = u
        repeat(8) {
            val error = bezier(s, x1, x2) - u
            if (abs(error) < EPSILON) return s
            val slope = bezierSlope(s, x1, x2)
            if (abs(slope) < 1e-6) return@repeat
            s -= error / slope
        }
        var low = 0.0
        var high = 1.0
        s = u
        while (high - low > EPSILON) {
            if (bezier(s, x1, x2) < u) low = s else high = s
            s = (low + high) / 2
        }
        return s
    }

    private companion object {
        const val EPSILON = 1e-9

        fun bezier(s: Double, p1: Double, p2: Double): Double {
            val inv = 1 - s
            return 3 * inv * inv * s * p1 + 3 * inv * s * s * p2 + s * s * s
        }

        fun bezierSlope(s: Double, p1: Double, p2: Double): Double {
            val inv = 1 - s
            return 3 * inv * inv * p1 + 6 * inv * s * (p2 - p1) + 3 * s * s * (1 - p2)
        }
    }
}

/**
 * Damped spring with unit mass, as in Compose `spring(dampingRatio, stiffness)`:
 * natural frequency = sqrt(stiffness) rad/s.
 */
data class Spring(val dampingRatio: Double, val stiffness: Double) {

    val naturalFrequency: Double get() = sqrt(stiffness)

    /** Step response from 0 to 1 with zero initial velocity, [seconds] after release. */
    fun valueAt(seconds: Double): Double {
        if (seconds <= 0.0) return 0.0
        val w = naturalFrequency
        val z = dampingRatio
        return when {
            z < 1.0 -> {
                val wd = w * sqrt(1 - z * z)
                1 - exp(-z * w * seconds) * (cos(wd * seconds) + (z * w / wd) * sin(wd * seconds))
            }
            z == 1.0 -> 1 - exp(-w * seconds) * (1 + w * seconds)
            else -> {
                val root = sqrt(z * z - 1)
                val r1 = -w * (z - root)
                val r2 = -w * (z + root)
                1 - (r2 * exp(r1 * seconds) - r1 * exp(r2 * seconds)) / (r2 - r1)
            }
        }
    }

    /** First time after which the response stays within [tolerance] of 1. */
    fun settleSeconds(tolerance: Double = 0.01): Double {
        val step = 0.001
        var lastOutside = 0.0
        var t = 0.0
        while (t < MAX_SETTLE_SECONDS) {
            if (abs(1 - valueAt(t)) > tolerance) lastOutside = t
            t += step
        }
        return lastOutside + step
    }

    private companion object {
        const val MAX_SETTLE_SECONDS = 10.0
    }
}
