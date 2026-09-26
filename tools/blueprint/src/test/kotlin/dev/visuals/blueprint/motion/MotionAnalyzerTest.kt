package dev.visuals.blueprint.motion

import dev.visuals.blueprint.model.AnimatedProperty
import dev.visuals.blueprint.model.EasingSpec
import dev.visuals.blueprint.model.MotionElement
import dev.visuals.blueprint.model.PropertyAnimation
import dev.visuals.blueprint.model.Reference
import dev.visuals.blueprint.model.Tracking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** End-to-end: synthetic clips with known motion through [MotionAnalyzer]. */
class MotionAnalyzerTest {

    @TempDir
    lateinit var dir: File

    private fun analyze(durationMs: Double, card: (Double) -> SyntheticClip.Card): MotionElement {
        val clip = File(dir, "clip.mkv")
        SyntheticClip.write(clip, durationMs, card)
        val spec = MotionAnalyzer().analyze(clip)
        return spec.elements.single()
    }

    private fun MotionElement.animation(property: AnimatedProperty): PropertyAnimation =
        animations.single { it.property == property }

    /** Largest progress difference between a fitted Bézier and the true one over the true motion. */
    private fun curveError(fit: PropertyAnimation, truth: CubicBezier, startMs: Double, durationMs: Double): Double {
        val easing = assertIs<EasingSpec.CubicBezier>(fit.easing)
        val fitted = CubicBezier(easing.x1, easing.y1, easing.x2, easing.y2)
        return (0..100).maxOf { i ->
            val t = startMs + durationMs * i / 100
            abs(fitted.valueAt((t - fit.startMs) / fit.durationMs) - truth.valueAt((t - startMs) / durationMs))
        }
    }

    @Test
    fun `measures a cubic-bezier slide`() {
        val truth = CubicBezier(0.2, 0.0, 0.0, 1.0)
        val element = analyze(1000.0) { t -> SyntheticClip.Card(cx = 100 + 160 * truth.valueAt((t - 200) / 400), cy = 320.0) }

        assertEquals(Tracking.FEATURES, element.tracking)
        assertEquals(listOf(AnimatedProperty.TRANSLATION_X), element.animations.map { it.property })
        val slide = element.animation(AnimatedProperty.TRANSLATION_X)
        assertEquals(0.0, slide.from, 0.5)
        assertEquals(160.0, slide.to, 1.5)
        assertEquals(200.0, slide.startMs, 12.0)
        assertEquals(400.0, slide.durationMs, 25.0)
        val error = curveError(slide, truth, 200.0, 400.0)
        assertTrue(error < 0.05, "max progress error $error")
    }

    @Test
    fun `measures a spring and prefers it over a bezier`() {
        val truth = Spring(dampingRatio = 0.4, stiffness = 300.0)
        val element = analyze(1500.0) { t -> SyntheticClip.Card(cx = 180.0, cy = 200 + 200 * truth.valueAt((t - 150) / 1000)) }

        val drop = element.animation(AnimatedProperty.TRANSLATION_Y)
        val spring = assertIs<EasingSpec.Spring>(drop.easing)
        assertEquals(0.4, spring.dampingRatio, 0.08)
        assertEquals(300.0, spring.stiffness, 300.0 * 0.15)
        assertEquals(200.0, drop.to, 2.0)
        assertTrue(drop.fit.springRmse < drop.fit.cubicBezierRmse)
    }

    @Test
    fun `measures scale about the centre without a false translation`() {
        val truth = CubicBezier(0.42, 0.0, 0.58, 1.0)
        val element = analyze(700.0) { t -> SyntheticClip.Card(cx = 180.0, cy = 320.0, scale = 1 + 0.5 * truth.valueAt((t - 100) / 300)) }

        assertEquals(listOf(AnimatedProperty.SCALE), element.animations.map { it.property })
        val grow = element.animation(AnimatedProperty.SCALE)
        assertEquals(1.0, grow.from, 0.01)
        assertEquals(1.5, grow.to, 0.03)
    }

    @Test
    fun `measures rotation about the centre`() {
        val truth = CubicBezier(0.42, 0.0, 0.58, 1.0)
        val element = analyze(700.0) { t -> SyntheticClip.Card(cx = 180.0, cy = 320.0, rotation = 45 * truth.valueAt((t - 100) / 300)) }

        assertEquals(listOf(AnimatedProperty.ROTATION), element.animations.map { it.property })
        val turn = element.animation(AnimatedProperty.ROTATION)
        assertEquals(0.0, turn.from, 0.1)
        assertEquals(45.0, turn.to, 1.0)
        val error = curveError(turn, truth, 100.0, 300.0)
        assertTrue(error < 0.05, "max progress error $error")
    }

    @Test
    fun `measures a fade-in as opacity`() {
        val linear = CubicBezier(0.0, 0.0, 1.0, 1.0)
        val element = analyze(600.0) { t -> SyntheticClip.Card(cx = 180.0, cy = 320.0, alpha = linear.valueAt((t - 100) / 300)) }

        assertEquals(Tracking.INTENSITY, element.tracking)
        assertEquals(Reference.END, element.reference)
        val fade = element.animation(AnimatedProperty.ALPHA)
        assertEquals(0.0, fade.from, 0.02)
        assertEquals(1.0, fade.to, 0.02)
        assertEquals(300.0, fade.durationMs, 30.0)
        val error = curveError(fade, linear, 100.0, 300.0)
        assertTrue(error < 0.05, "max progress error $error")
    }
}
