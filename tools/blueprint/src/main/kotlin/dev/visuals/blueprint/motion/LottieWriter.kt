package dev.visuals.blueprint.motion

import dev.visuals.blueprint.model.AnimatedProperty
import dev.visuals.blueprint.model.EasingSpec
import dev.visuals.blueprint.model.MotionElement
import dev.visuals.blueprint.model.MotionSpec
import dev.visuals.blueprint.model.PropertyAnimation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Writes a Lottie (bodymovin) animation with one rectangle layer per element, filled with the
 * element's measured colour and carrying its measured motion. Bézier easings become keyframe
 * tangents; springs have no Lottie equivalent and are baked into one keyframe per frame.
 * The artwork is a placeholder: the motion transfers, the element's look does not.
 */
object LottieWriter {

    /** lottie-android reads the bodymovin `v` field and warns below 4.4.0. */
    private const val BODYMOVIN_VERSION = "5.7.0"
    private const val DEFAULT_FPS = 60.0

    fun write(spec: MotionSpec): JsonObject {
        val fps = spec.video.fps.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_FPS
        val outFrame = ceil(spec.video.durationMs / 1000 * fps)
        return buildJsonObject {
            put("v", BODYMOVIN_VERSION)
            put("nm", spec.source.file)
            put("fr", fps)
            put("ip", 0)
            put("op", outFrame)
            put("w", spec.video.width)
            put("h", spec.video.height)
            put("ddd", 0)
            putJsonArray("assets") {}
            putJsonArray("layers") {
                spec.elements.forEachIndexed { i, element -> add(layer(element, i + 1, fps, outFrame)) }
            }
        }
    }

    private fun layer(element: MotionElement, index: Int, fps: Double, outFrame: Double): JsonObject {
        // The rectangle is drawn centred on the layer origin; anchor and position sit on the pivot.
        val b = element.bounds
        val px = element.pivot.x
        val py = element.pivot.y
        val anchor = listOf(px - (b.x + b.width / 2), py - (b.y + b.height / 2), 0.0)
        val byProperty = element.animations.associateBy { it.property }
        fun animated(property: AnimatedProperty, rest: List<Double>, toValue: (Double) -> List<Double>): JsonObject =
            byProperty[property]?.let { keyframes(it, fps, toValue) } ?: staticValue(rest)

        return buildJsonObject {
            put("ddd", 0)
            put("ind", index)
            put("ty", 4)
            put("nm", element.id)
            put("sr", 1)
            put("ao", 0)
            put("ip", 0)
            put("op", outFrame)
            put("st", 0)
            put("bm", 0)
            putJsonObject("ks") {
                put("a", staticValue(anchor))
                putJsonObject("p") {
                    put("s", true)
                    put("x", animated(AnimatedProperty.TRANSLATION_X, listOf(px)) { listOf(px + it) })
                    put("y", animated(AnimatedProperty.TRANSLATION_Y, listOf(py)) { listOf(py + it) })
                }
                put("s", animated(AnimatedProperty.SCALE, listOf(100.0, 100.0, 100.0)) { listOf(it * 100, it * 100, 100.0) })
                put("r", animated(AnimatedProperty.ROTATION, listOf(0.0)) { listOf(it) })
                put("o", animated(AnimatedProperty.ALPHA, listOf(100.0)) { listOf(it * 100) })
            }
            putJsonArray("shapes") {
                addJsonObject {
                    put("ty", "gr")
                    put("nm", "box")
                    putJsonArray("it") {
                        addJsonObject {
                            put("ty", "rc")
                            put("d", 1)
                            put("s", staticValue(listOf(b.width, b.height)))
                            put("p", staticValue(listOf(0.0, 0.0)))
                            put("r", staticValue(listOf(0.0)))
                        }
                        addJsonObject {
                            put("ty", "fl")
                            put("c", staticValue(rgba(element.color)))
                            put("o", staticValue(listOf(100.0)))
                            put("r", 1)
                        }
                        addJsonObject {
                            put("ty", "tr")
                            put("p", staticValue(listOf(0.0, 0.0)))
                            put("a", staticValue(listOf(0.0, 0.0)))
                            put("s", staticValue(listOf(100.0, 100.0)))
                            put("r", staticValue(listOf(0.0)))
                            put("o", staticValue(listOf(100.0)))
                        }
                    }
                }
            }
        }
    }

    /** Keyframes of the animation, each value mapped to Lottie's representation by [value]. */
    private fun keyframes(animation: PropertyAnimation, fps: Double, value: (Double) -> List<Double>): JsonObject {
        fun frame(ms: Double) = ms / 1000 * fps
        val dimensions = value(animation.from).size
        val keys = buildJsonArray {
            when (val easing = animation.easing) {
                is EasingSpec.CubicBezier -> {
                    add(keyframe(frame(animation.startMs), value(animation.from), dimensions, easing.x1, easing.y1, easing.x2, easing.y2))
                    add(keyframe(frame(animation.startMs + animation.durationMs), value(animation.to), dimensions))
                }
                is EasingSpec.Spring -> {
                    val spring = Spring(easing.dampingRatio, easing.stiffness)
                    val first = frame(animation.startMs)
                    val last = frame(animation.startMs + animation.durationMs)
                    val frames = listOf(first) + (floor(first).toInt() + 1..ceil(last).toInt()).map(Int::toDouble)
                    frames.forEachIndexed { i, f ->
                        val progress = spring.valueAt((f - first) / fps)
                        val v = value(animation.from + (animation.to - animation.from) * progress)
                        if (i < frames.lastIndex) add(keyframe(f, v, dimensions, 0.0, 0.0, 1.0, 1.0)) else add(keyframe(f, value(animation.to), dimensions))
                    }
                }
            }
        }
        return buildJsonObject {
            put("a", 1)
            put("k", keys)
        }
    }

    /** A keyframe; with tangents it eases towards the next keyframe (`o` leaves this one, `i` enters the next). */
    private fun keyframe(frame: Double, value: List<Double>, dimensions: Int, vararg tangents: Double): JsonObject = buildJsonObject {
        put("t", frame)
        put("s", numbers(value))
        if (tangents.isNotEmpty()) {
            putJsonObject("o") {
                put("x", numbers(List(dimensions) { tangents[0] }))
                put("y", numbers(List(dimensions) { tangents[1] }))
            }
            putJsonObject("i") {
                put("x", numbers(List(dimensions) { tangents[2] }))
                put("y", numbers(List(dimensions) { tangents[3] }))
            }
        }
    }

    private fun staticValue(value: List<Double>): JsonObject = buildJsonObject {
        put("a", 0)
        put("k", if (value.size == 1) JsonPrimitive(value.single()) else numbers(value))
    }

    private fun numbers(values: List<Double>): JsonArray = buildJsonArray { values.forEach { add(it) } }

    private fun rgba(hex: String): List<Double> {
        val rgb = hex.removePrefix("#").toInt(16)
        return listOf((rgb shr 16 and 0xFF) / 255.0, (rgb shr 8 and 0xFF) / 255.0, (rgb and 0xFF) / 255.0, 1.0)
    }
}
