package dev.visuals.blueprint.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveFitterTest {

    private val frameMs = 1000.0 / 60

    private fun frames(untilMs: Double, progress: (Double) -> Double): List<ProgressSample> =
        generateSequence(0.0) { it + frameMs }.takeWhile { it <= untilMs }.map { ProgressSample(it, progress(it)) }.toList()

    @Test
    fun `recovers a cubic bezier and sub-frame timing`() {
        val truth = CubicBezier(0.2, 0.0, 0.0, 1.0)
        val startMs = 101.3
        val durationMs = 400.0
        val samples = frames(800.0) { t -> truth.valueAt((t - startMs) / durationMs) }

        val fit = CurveFitter.fitBezier(samples, startGuessMs = 100.0, durationGuessMs = 416.7)

        assertEquals(startMs, fit.startMs, 3.0)
        assertEquals(durationMs, fit.durationMs, 6.0)
        val worst = (0..100).maxOf { i ->
            val t = startMs + durationMs * i / 100
            abs(fit.curve.valueAt((t - fit.startMs) / fit.durationMs) - truth.valueAt((t - startMs) / durationMs))
        }
        assertTrue(worst < 0.02, "max progress error $worst")
        assertTrue(fit.rmse < 0.01, "rmse ${fit.rmse}")
    }

    @Test
    fun `recovers spring damping and stiffness`() {
        val truth = Spring(dampingRatio = 0.5, stiffness = 400.0)
        val startMs = 51.0
        val samples = frames(1500.0) { t -> truth.valueAt((t - startMs) / 1000) }

        val fit = CurveFitter.fitSpring(samples, startGuessMs = 50.0, durationGuessMs = truth.settleSeconds() * 1000)

        assertEquals(0.5, fit.spring.dampingRatio, 0.02)
        assertEquals(400.0, fit.spring.stiffness, 400.0 * 0.05)
        assertEquals(startMs, fit.startMs, 3.0)
        assertTrue(fit.rmse < 0.01, "rmse ${fit.rmse}")
    }

    @Test
    fun `evaluates known easing and spring values`() {
        assertEquals(0.5, CubicBezier(0.42, 0.0, 0.58, 1.0).valueAt(0.5), 1e-6)
        assertEquals(0.3, CubicBezier(0.0, 0.0, 1.0, 1.0).valueAt(0.3), 1e-6)
        assertEquals(1.0, Spring(1.0, 400.0).valueAt(2.0), 1e-6)
        assertTrue(Spring(0.2, 400.0).valueAt(0.16) > 1.0, "underdamped spring overshoots")
    }
}
