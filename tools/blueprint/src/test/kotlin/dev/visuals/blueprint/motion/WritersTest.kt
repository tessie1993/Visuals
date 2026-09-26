package dev.visuals.blueprint.motion

import dev.visuals.blueprint.model.AnimatedProperty
import dev.visuals.blueprint.model.Bounds
import dev.visuals.blueprint.model.EasingSpec
import dev.visuals.blueprint.model.FitQuality
import dev.visuals.blueprint.model.MotionElement
import dev.visuals.blueprint.model.MotionSpec
import dev.visuals.blueprint.model.Pivot
import dev.visuals.blueprint.model.PropertyAnimation
import dev.visuals.blueprint.model.Reference
import dev.visuals.blueprint.model.Source
import dev.visuals.blueprint.model.Tracking
import dev.visuals.blueprint.model.VideoInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritersTest {

    private val spec = MotionSpec(
        schemaVersion = MotionSpec.SCHEMA_VERSION,
        source = Source("clip.mp4", "0"),
        video = VideoInfo(width = 1080, height = 2400, fps = 60.0, durationMs = 1000.0, frames = 60),
        elements = listOf(
            MotionElement(
                id = "element1",
                bounds = Bounds(100.0, 200.0, 300.0, 150.0),
                pivot = Pivot(250.0, 275.0),
                color = "#3F51B5",
                tracking = Tracking.FEATURES,
                reference = Reference.START,
                animations = listOf(
                    PropertyAnimation(
                        property = AnimatedProperty.TRANSLATION_X,
                        from = 0.0, to = 262.5, startMs = 100.0, durationMs = 400.0,
                        easing = EasingSpec.CubicBezier(0.2, 0.0, 0.0, 1.0),
                        fit = FitQuality(0.01, 0.05), samples = emptyList(),
                    ),
                    PropertyAnimation(
                        property = AnimatedProperty.ALPHA,
                        from = 0.0, to = 1.0, startMs = 300.0, durationMs = 250.0,
                        easing = EasingSpec.Spring(dampingRatio = 0.5, stiffness = 400.0),
                        fit = FitQuality(0.05, 0.01), samples = emptyList(),
                    ),
                ),
            ),
        ),
        warnings = emptyList(),
    )

    @Test
    fun `compose code converts px to dp, delays from the first motion, and uses tween or spring`() {
        val code = ComposeWriter.write(spec, "com.example.motion", pxPerDp = 2.625)
        val expected = listOf(
            "package com.example.motion",
            "import androidx.compose.animation.core.spring",
            "import androidx.compose.animation.core.tween",
            "fun Element1Motion(play: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {",
            "    val offsetX = remember { Animatable(0f) }",
            "            offsetX.animateTo(100f, tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))",
            "            delay(200L)",
            "            opacity.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))",
            "            translationX = offsetX.value.dp.toPx()",
            "            alpha = opacity.value",
        )
        val lines = code.lines()
        expected.forEach { assertTrue(it in lines, "missing line: $it\n$code") }
    }

    @Test
    fun `lottie keyframes carry bezier tangents and baked springs`() {
        val lottie = LottieWriter.write(spec)
        assertEquals("5.7.0", lottie["v"]!!.jsonPrimitive.content)
        assertEquals(60.0, lottie["fr"]!!.jsonPrimitive.double)
        assertEquals(60.0, lottie["op"]!!.jsonPrimitive.double)

        val transform = lottie["layers"]!!.jsonArray.single().jsonObject["ks"]!!.jsonObject
        val position = transform["p"]!!.jsonObject
        assertTrue(position["s"]!!.jsonPrimitive.boolean)
        val x = position["x"]!!.jsonObject["k"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(6.0, 30.0), x.map { it["t"]!!.jsonPrimitive.double })
        assertEquals(listOf(250.0, 512.5), x.map { it.first("s") })
        assertEquals(0.2, x[0]["o"]!!.jsonObject.first("x"))
        assertEquals(1.0, x[0]["i"]!!.jsonObject.first("y"))

        val opacity = transform["o"]!!.jsonObject["k"]!!.jsonArray.map { it.jsonObject }
        assertEquals(18.0, opacity.first()["t"]!!.jsonPrimitive.double)
        assertEquals(0.0, opacity.first().first("s"))
        assertEquals(100.0, opacity.last().first("s"))
        assertTrue(opacity.size > 10, "spring baked into per-frame keyframes")
        assertTrue(opacity.any { it.first("s") > 100.0 }, "underdamped spring overshoots")
    }

    @Test
    fun `an off-centre pivot becomes the transform origin and the lottie anchor`() {
        val turning = spec.copy(
            elements = listOf(
                spec.elements.single().copy(
                    pivot = Pivot(100.0, 200.0),
                    animations = listOf(
                        spec.elements.single().animations.first().copy(property = AnimatedProperty.ROTATION, from = 0.0, to = 90.0),
                    ),
                ),
            ),
        )
        val code = ComposeWriter.write(turning, "motion", pxPerDp = 1.0)
        assertTrue("            transformOrigin = TransformOrigin(0f, 0f)" in code.lines(), code)
        assertTrue("import androidx.compose.ui.graphics.TransformOrigin" in code.lines(), code)

        val transform = LottieWriter.write(turning)["layers"]!!.jsonArray.single().jsonObject["ks"]!!.jsonObject
        assertEquals(listOf(-150.0, -75.0, 0.0), transform["a"]!!.jsonObject["k"]!!.jsonArray.map { it.jsonPrimitive.double })
        assertEquals(100.0, transform["p"]!!.jsonObject["x"]!!.jsonObject["k"]!!.jsonPrimitive.double)
    }

    private fun JsonObject.first(key: String): Double = (this[key] as JsonArray).first().jsonPrimitive.double
}
