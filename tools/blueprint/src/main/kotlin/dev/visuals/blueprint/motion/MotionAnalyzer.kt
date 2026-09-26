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
import dev.visuals.blueprint.sha256
import org.bytedeco.javacpp.indexer.IntIndexer
import org.bytedeco.javacpp.indexer.UByteIndexer
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_core.absdiff
import org.bytedeco.opencv.global.opencv_core.bitwise_or
import org.bytedeco.opencv.global.opencv_core.countNonZero
import org.bytedeco.opencv.global.opencv_core.mean
import org.bytedeco.opencv.global.opencv_core.subtract
import org.bytedeco.opencv.global.opencv_imgproc.MORPH_ELLIPSE
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY
import org.bytedeco.opencv.global.opencv_imgproc.connectedComponentsWithStats
import org.bytedeco.opencv.global.opencv_imgproc.dilate
import org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement
import org.bytedeco.opencv.global.opencv_imgproc.threshold
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class MotionOptions(
    /** Longest side of the frames the analysis runs on; larger videos are downscaled. */
    val maxAnalysisSize: Int = 720,
    /** Grey-level difference for a pixel to count as changed. */
    val pixelThreshold: Double = 8.0,
    /** Pauses up to this long do not split a motion segment. */
    val mergeGapMs: Double = 120.0,
)

/**
 * Measures UI motion in a video: finds the time segments where something moves, the areas that
 * move, and per area either a similarity transform (feature tracking) or an opacity change
 * (mean intensity), then fits a cubic-Bézier and a spring to each changing property.
 */
class MotionAnalyzer(private val options: MotionOptions = MotionOptions(), private val log: (String) -> Unit = {}) {

    private data class Segment(val firstActive: Int, val lastActive: Int) {
        val rest get() = firstActive - 1
        val settled get() = lastActive
    }

    /**
     * A moving area. [region] is the changed pixels grown to join nearby changes (where features are
     * searched); [changed] is the changed pixels only (bounds and brightness).
     */
    private data class Area(val region: Mat, val changed: Mat, val box: Rect)

    fun analyze(file: File): MotionSpec {
        log("Decoding ${file.name}")
        VideoReader.read(file, options.maxAnalysisSize).use { video ->
            val warnings = mutableListOf<String>()
            val segments = segments(video)
            log("Found ${segments.size} motion segments in ${video.frameCount} frames")
            if (segments.isEmpty()) warnings += "No motion found."
            val elements = segments.flatMap { segment ->
                areas(video, segment).mapNotNull { area -> area.use { measure(video, segment, it, warnings) } }
            }.mapIndexed { i, element -> element.copy(id = "element${i + 1}") }
            return MotionSpec(
                schemaVersion = MotionSpec.SCHEMA_VERSION,
                source = Source(file.name, file.sha256()),
                video = VideoInfo(
                    width = video.width,
                    height = video.height,
                    fps = video.fps,
                    durationMs = video.timesMs.last(),
                    frames = video.frameCount,
                ),
                elements = elements,
                warnings = warnings,
            )
        }
    }

    // --- where and when things move ---

    private fun changeMask(video: DecodedVideo, frame: Int): Mat {
        val mask = Mat()
        Mat().use { diff ->
            absdiff(video.gray[frame], video.gray[frame - 1], diff)
            threshold(diff, mask, options.pixelThreshold, 255.0, THRESH_BINARY)
        }
        return mask
    }

    private fun segments(video: DecodedVideo): List<Segment> {
        val minChanged = max(MIN_CHANGED_PIXELS, video.gray[0].total() * MIN_CHANGED_SHARE)
        val active = (1 until video.frameCount).filter { frame ->
            changeMask(video, frame).use { countNonZero(it) >= minChanged }
        }
        val segments = mutableListOf<Segment>()
        for (frame in active) {
            val last = segments.lastOrNull()
            if (last != null && video.timesMs[frame] - video.timesMs[last.lastActive] <= options.mergeGapMs) {
                segments[segments.lastIndex] = last.copy(lastActive = frame)
            } else {
                segments += Segment(frame, frame)
            }
        }
        return segments
    }

    /** Connected regions of all pixels that change during the segment. */
    private fun areas(video: DecodedVideo, segment: Segment): List<Area> {
        val union = Mat(video.gray[0].size(), CV_8UC1, Scalar(0.0))
        for (frame in segment.firstActive..segment.lastActive) {
            changeMask(video, frame).use { bitwise_or(union, it, union) }
        }
        val joined = Mat()
        kernel(JOIN_KERNEL_PX).use { dilate(union, joined, it) }
        val labels = Mat()
        val stats = Mat()
        Mat().use { centroids -> connectedComponentsWithStats(joined, labels, stats, centroids) }
        joined.close()
        val areas = mutableListOf<Area>()
        stats.createIndexer<IntIndexer>().use { s ->
            labels.createIndexer<IntIndexer>().use { l ->
                union.createIndexer<UByteIndexer>().use { u ->
                    for (label in 1 until stats.rows()) {
                        if (s.get(label.toLong(), 4) < MIN_AREA_PX) continue
                        val region = Mat(labels.size(), CV_8UC1, Scalar(0.0))
                        val changed = Mat(labels.size(), CV_8UC1, Scalar(0.0))
                        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
                        region.createIndexer<UByteIndexer>().use { r ->
                            changed.createIndexer<UByteIndexer>().use { c ->
                                val left = s.get(label.toLong(), 0)
                                val top = s.get(label.toLong(), 1)
                                for (y in top until top + s.get(label.toLong(), 3)) {
                                    for (x in left until left + s.get(label.toLong(), 2)) {
                                        if (l.get(y.toLong(), x.toLong()) != label) continue
                                        r.put(y.toLong(), x.toLong(), 255)
                                        if (u.get(y.toLong(), x.toLong()) == 0) continue
                                        c.put(y.toLong(), x.toLong(), 255)
                                        x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
                                    }
                                }
                            }
                        }
                        areas += Area(region, changed, Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1))
                    }
                }
            }
        }
        union.close()
        labels.close()
        stats.close()
        return areas
    }

    // --- what moves and how ---

    private fun measure(video: DecodedVideo, segment: Segment, area: Area, warnings: MutableList<String>): MotionElement? {
        val range = segment.rest..segment.settled
        val frames = range.map { video.gray[it] }
        FeatureTracker.track(frames, area.region)?.let { return geometric(video, segment, area, it, Reference.START) }
        FeatureTracker.track(frames.reversed(), area.region)?.let { return geometric(video, segment, area, it.reversed(), Reference.END) }
        return opacity(video, segment, area, warnings)
    }

    private fun geometric(video: DecodedVideo, segment: Segment, area: Area, track: Track, reference: Reference): MotionElement? {
        val px = video.pixelScale
        val values = mapOf(
            AnimatedProperty.TRANSLATION_X to track.poses.map { it?.dx?.times(px) },
            AnimatedProperty.TRANSLATION_Y to track.poses.map { it?.dy?.times(px) },
            AnimatedProperty.SCALE to track.poses.map { it?.scale },
            AnimatedProperty.ROTATION to track.poses.map { it?.rotationDegrees },
        )
        val animations = values.mapNotNull { (property, series) -> animation(video, segment, property, series) }
        if (animations.isEmpty()) return null
        val referenceFrame = if (reference == Reference.START) segment.rest else segment.settled
        Rect(track.minX.toInt(), track.minY.toInt(), (track.maxX - track.minX).roundToInt() + 1, (track.maxY - track.minY).roundToInt() + 1).use { box ->
            return MotionElement(
                id = "",
                bounds = bounds(box, px),
                pivot = Pivot((track.pivot.x * px).round(1), (track.pivot.y * px).round(1)),
                color = rectMask(video, box).use { colourOf(video.colour[referenceFrame], it) },
                tracking = Tracking.FEATURES,
                reference = reference,
                animations = animations,
            )
        }
    }

    /** Fade in or out: the region changes brightness without moving. */
    private fun opacity(video: DecodedVideo, segment: Segment, area: Area, warnings: MutableList<String>): MotionElement? {
        val means = (segment.rest..segment.settled).map { mean(video.gray[it], area.changed).get(0) }
        val change = means.last() - means.first()
        if (abs(change) < MIN_INTENSITY_CHANGE) {
            warnings += "A region at ${bounds(area.box, video.pixelScale)} changes but neither moves nor fades measurably."
            return null
        }
        val surroundings = ring(area.changed).use { ring -> mean(video.gray[segment.rest], ring).get(0) }
        val fadesIn = abs(means.first() - surroundings) < abs(means.last() - surroundings)
        val alpha = means.map { m -> ((m - means.first()) / change).let { if (fadesIn) it else 1 - it } }
        val animation = animation(video, segment, AnimatedProperty.ALPHA, alpha) ?: return null
        val visibleFrame = if (fadesIn) segment.settled else segment.rest
        val box = bounds(area.box, video.pixelScale)
        return MotionElement(
            id = "",
            bounds = box,
            pivot = Pivot((box.x + box.width / 2).round(1), (box.y + box.height / 2).round(1)),
            color = colourOf(video.colour[visibleFrame], area.changed),
            tracking = Tracking.INTENSITY,
            reference = if (fadesIn) Reference.END else Reference.START,
            animations = listOf(animation),
        )
    }

    /** Fits one property over the segment; null if it barely changes. */
    private fun animation(video: DecodedVideo, segment: Segment, property: AnimatedProperty, series: List<Double?>): PropertyAnimation? {
        val from = series.first() ?: return null
        val to = series.last() ?: return null
        if (abs(to - from) < MIN_CHANGE.getValue(property)) return null
        val first = max(0, segment.rest - PAD_FRAMES)
        val last = min(video.frameCount - 1, segment.settled + PAD_FRAMES)
        val samples = (first..last).mapNotNull { frame ->
            val value = when {
                frame <= segment.rest -> from
                frame >= segment.settled -> to
                else -> series[frame - segment.rest] ?: return@mapNotNull null
            }
            video.timesMs[frame] to value
        }
        val progress = samples.map { (t, v) -> ProgressSample(t, (v - from) / (to - from)) }
        val startGuess = video.timesMs[segment.rest]
        val durationGuess = video.timesMs[segment.settled] - startGuess
        val bezier = CurveFitter.fitBezier(progress, startGuess, durationGuess)
        val spring = CurveFitter.fitSpring(progress, startGuess, durationGuess)
        val useSpring = spring.rmse < bezier.rmse * SPRING_PREFERENCE
        return PropertyAnimation(
            property = property,
            from = from.round(3),
            to = to.round(3),
            startMs = (if (useSpring) spring.startMs else bezier.startMs).round(1),
            durationMs = (if (useSpring) spring.spring.settleSeconds() * 1000 else bezier.durationMs).round(1),
            easing = if (useSpring) {
                EasingSpec.Spring(spring.spring.dampingRatio.round(3), spring.spring.stiffness.round(1))
            } else {
                bezier.curve.let { EasingSpec.CubicBezier(it.x1.round(3), it.y1.round(3), it.x2.round(3), it.y2.round(3)) }
            },
            fit = FitQuality(bezier.rmse.round(4), spring.rmse.round(4)),
            samples = samples.map { (t, v) -> listOf(t.round(2), v.round(3)) },
        )
    }

    // --- helpers ---

    private fun bounds(box: Rect, px: Double) =
        Bounds((box.x() * px).round(1), (box.y() * px).round(1), (box.width() * px).round(1), (box.height() * px).round(1))

    private fun rectMask(video: DecodedVideo, box: Rect): Mat =
        Mat(video.gray[0].size(), CV_8UC1, Scalar(0.0)).also { mask ->
            Mat(mask, box).use { roi -> roi.put(Scalar(255.0)) }
        }

    private fun colourOf(frame: Mat, mask: Mat): String {
        val bgr = mean(frame, mask)
        return "#%02X%02X%02X".format(bgr.get(2).roundToInt(), bgr.get(1).roundToInt(), bgr.get(0).roundToInt())
    }

    /** Pixels just outside the mask. */
    private fun ring(mask: Mat): Mat {
        val grown = Mat()
        kernel(RING_KERNEL_PX).use { dilate(mask, grown, it) }
        subtract(grown, mask, grown)
        return grown
    }

    private fun kernel(size: Int): Mat = Size(size, size).use { getStructuringElement(MORPH_ELLIPSE, it) }

    private fun Area.use(block: (Area) -> MotionElement?): MotionElement? = try {
        block(this)
    } finally {
        region.close()
        changed.close()
        box.close()
    }

    private fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }

    private companion object {
        const val MIN_CHANGED_PIXELS = 4.0
        const val MIN_CHANGED_SHARE = 0.00005
        const val MIN_AREA_PX = 30
        const val JOIN_KERNEL_PX = 7
        const val RING_KERNEL_PX = 15
        const val MIN_INTENSITY_CHANGE = 2.0
        const val PAD_FRAMES = 3

        /** A spring must beat the Bézier fit by this factor to be chosen; Bézier also gives a duration. */
        const val SPRING_PREFERENCE = 0.8

        val MIN_CHANGE = mapOf(
            AnimatedProperty.TRANSLATION_X to 1.0,
            AnimatedProperty.TRANSLATION_Y to 1.0,
            AnimatedProperty.SCALE to 0.02,
            AnimatedProperty.ROTATION to 1.0,
            AnimatedProperty.ALPHA to 0.05,
        )
    }
}
