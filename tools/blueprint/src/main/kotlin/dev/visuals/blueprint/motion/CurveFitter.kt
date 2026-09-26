package dev.visuals.blueprint.motion

import org.hipparchus.linear.Array2DRowRealMatrix
import org.hipparchus.linear.ArrayRealVector
import org.hipparchus.linear.RealVector
import org.hipparchus.optim.nonlinear.vector.leastsquares.LeastSquaresBuilder
import org.hipparchus.optim.nonlinear.vector.leastsquares.LevenbergMarquardtOptimizer
import org.hipparchus.optim.nonlinear.vector.leastsquares.MultivariateJacobianFunction
import org.hipparchus.optim.nonlinear.vector.leastsquares.ParameterValidator
import org.hipparchus.util.Pair
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Normalised progress (0 at rest before, 1 at rest after) measured at [timeMs]. */
data class ProgressSample(val timeMs: Double, val progress: Double)

data class BezierFit(val startMs: Double, val durationMs: Double, val curve: CubicBezier, val rmse: Double)

data class SpringFit(val startMs: Double, val spring: Spring, val rmse: Double)

/**
 * Least-squares fits of a progress curve. Start time (and duration for Bézier) are free parameters,
 * so motion that begins or ends between two frames is still timed to sub-frame precision.
 * Times are normalised by the duration guess internally to keep the problem well conditioned.
 */
object CurveFitter {

    private val BEZIER_STARTS = listOf(
        CubicBezier(0.25, 0.1, 0.25, 1.0),
        CubicBezier(0.42, 0.0, 0.58, 1.0),
        CubicBezier(0.33, 0.33, 0.67, 0.67),
        CubicBezier(0.05, 0.7, 0.1, 1.0),
        CubicBezier(0.42, 0.0, 1.0, 1.0),
        CubicBezier(0.3, 0.0, 0.2, 1.4),
    )
    private val DAMPING_STARTS = listOf(0.3, 0.6, 0.9, 1.2)

    fun fitBezier(samples: List<ProgressSample>, startGuessMs: Double, durationGuessMs: Double): BezierFit {
        val scale = durationGuessMs
        val times = samples.map { (it.timeMs - startGuessMs) / scale }
        val target = samples.map { it.progress }.toDoubleArray()
        fun model(p: DoubleArray, t: Double): Double {
            val u = (t - p[0]) / p[1]
            return CubicBezier(p[2], p[3], p[4], p[5]).valueAt(u)
        }
        val validator = ParameterValidator { v ->
            val p = v.toArray()
            p[1] = max(p[1], 1e-3)
            p[2] = p[2].coerceIn(0.0, 1.0)
            p[4] = p[4].coerceIn(0.0, 1.0)
            p[3] = p[3].coerceIn(-1.0, 2.0)
            p[5] = p[5].coerceIn(-1.0, 2.0)
            ArrayRealVector(p)
        }
        val best = BEZIER_STARTS
            .map { c -> optimize(doubleArrayOf(0.0, 1.0, c.x1, c.y1, c.x2, c.y2), times, target, validator, ::model) }
            .minBy { it.second }
        val p = best.first
        return BezierFit(
            startMs = startGuessMs + p[0] * scale,
            durationMs = p[1] * scale,
            curve = CubicBezier(p[2], p[3], p[4], p[5]),
            rmse = best.second,
        )
    }

    fun fitSpring(samples: List<ProgressSample>, startGuessMs: Double, durationGuessMs: Double): SpringFit {
        val scale = durationGuessMs
        val times = samples.map { (it.timeMs - startGuessMs) / scale }
        val target = samples.map { it.progress }.toDoubleArray()
        // p = [start, dampingRatio, naturalFrequency]; frequency in radians per `scale`.
        fun model(p: DoubleArray, t: Double): Double =
            Spring(p[1], p[2] * p[2]).valueAt(t - p[0])
        val validator = ParameterValidator { v ->
            val p = v.toArray()
            p[1] = p[1].coerceIn(0.02, 3.0)
            p[2] = max(p[2], 1e-3)
            ArrayRealVector(p)
        }
        val best = DAMPING_STARTS
            .map { z -> optimize(doubleArrayOf(0.0, z, 4.0 / z), times, target, validator, ::model) }
            .minBy { it.second }
        val p = best.first
        val frequencyPerSecond = p[2] / (scale / 1000.0)
        return SpringFit(
            startMs = startGuessMs + p[0] * scale,
            spring = Spring(dampingRatio = p[1], stiffness = frequencyPerSecond * frequencyPerSecond),
            rmse = best.second,
        )
    }

    /** Levenberg–Marquardt with a central-difference Jacobian; returns (parameters, RMSE). */
    private fun optimize(
        start: DoubleArray,
        times: List<Double>,
        target: DoubleArray,
        validator: ParameterValidator,
        model: (DoubleArray, Double) -> Double,
    ): kotlin.Pair<DoubleArray, Double> {
        val function = MultivariateJacobianFunction { point: RealVector ->
            val p = point.toArray()
            val values = DoubleArray(times.size) { model(p, times[it]) }
            val jacobian = Array(times.size) { DoubleArray(p.size) }
            for (j in p.indices) {
                val h = 1e-6 * max(1.0, abs(p[j]))
                val plus = p.copyOf().also { it[j] += h }
                val minus = p.copyOf().also { it[j] -= h }
                for (i in times.indices) {
                    jacobian[i][j] = (model(plus, times[i]) - model(minus, times[i])) / (2 * h)
                }
            }
            Pair(ArrayRealVector(values, false), Array2DRowRealMatrix(jacobian, false))
        }
        val problem = LeastSquaresBuilder()
            .start(start)
            .model(function)
            .target(target)
            .parameterValidator(validator)
            .maxEvaluations(MAX_EVALUATIONS)
            .maxIterations(MAX_EVALUATIONS)
            .build()
        val optimum = runCatching { LevenbergMarquardtOptimizer().optimize(problem) }.getOrNull()
            ?: return start to rmse(start, times, target, model)
        val point = optimum.point.toArray()
        return point to rmse(point, times, target, model)
    }

    private fun rmse(p: DoubleArray, times: List<Double>, target: DoubleArray, model: (DoubleArray, Double) -> Double): Double =
        sqrt(times.indices.sumOf { i -> (model(p, times[i]) - target[i]).let { it * it } } / times.size)

    private const val MAX_EVALUATIONS = 2000
}
