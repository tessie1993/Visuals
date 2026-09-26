package dev.visuals.blueprint.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Motion measured from one video. Distances are in video pixels, times in milliseconds from the first frame. */
@Serializable
data class MotionSpec(
    val schemaVersion: Int,
    val source: Source,
    val video: VideoInfo,
    val elements: List<MotionElement>,
    val warnings: List<String>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class VideoInfo(val width: Int, val height: Int, val fps: Double, val durationMs: Double, val frames: Int)

@Serializable
data class MotionElement(
    val id: String,
    /** Approximate bounds in the [reference] frame. */
    val bounds: Bounds,
    /** Point scale and rotation turn about, in the [reference] frame; translation is measured here. */
    val pivot: Pivot,
    /** Mean colour of the element in the [reference] frame, `#RRGGBB`. */
    val color: String,
    val tracking: Tracking,
    /** Frame the values are relative to: before the motion, or after it for elements that enter. */
    val reference: Reference,
    val animations: List<PropertyAnimation>,
)

@Serializable
data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class Pivot(val x: Double, val y: Double)

@Serializable
enum class Tracking {
    /** Feature points followed with optical flow; gives translation, scale and rotation. */
    @SerialName("features") FEATURES,
    /** Mean brightness of a region that changes without moving; gives opacity. */
    @SerialName("intensity") INTENSITY,
}

@Serializable
enum class Reference {
    @SerialName("start") START,
    @SerialName("end") END,
}

@Serializable
data class PropertyAnimation(
    val property: AnimatedProperty,
    val from: Double,
    val to: Double,
    val startMs: Double,
    /** Bézier: the fitted duration. Spring: time until it stays within 1% of the end value. */
    val durationMs: Double,
    val easing: EasingSpec,
    val fit: FitQuality,
    /** Measured `[timeMs, value]` pairs the fit was made from. */
    val samples: List<List<Double>>,
)

@Serializable
enum class AnimatedProperty(val unit: String) {
    @SerialName("translationX") TRANSLATION_X("px"),
    @SerialName("translationY") TRANSLATION_Y("px"),
    @SerialName("scale") SCALE("factor"),
    @SerialName("rotation") ROTATION("degrees"),
    @SerialName("alpha") ALPHA("0..1"),
}

@Serializable
sealed interface EasingSpec {
    @Serializable
    @SerialName("cubic-bezier")
    data class CubicBezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : EasingSpec

    @Serializable
    @SerialName("spring")
    data class Spring(val dampingRatio: Double, val stiffness: Double) : EasingSpec
}

/** Root-mean-square error of both candidate fits, in progress units (0..1). */
@Serializable
data class FitQuality(val cubicBezierRmse: Double, val springRmse: Double)
