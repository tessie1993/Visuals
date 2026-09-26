package dev.visuals.blueprint.motion

import org.bytedeco.javacpp.indexer.DoubleIndexer
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.javacpp.indexer.UByteIndexer
import org.bytedeco.opencv.global.opencv_calib3d.estimateAffinePartial2D
import org.bytedeco.opencv.global.opencv_core.CV_32FC2
import org.bytedeco.opencv.global.opencv_core.CV_64F
import org.bytedeco.opencv.global.opencv_core.absdiff
import org.bytedeco.opencv.global.opencv_core.mean
import org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.WARP_INVERSE_MAP
import org.bytedeco.opencv.global.opencv_imgproc.goodFeaturesToTrack
import org.bytedeco.opencv.global.opencv_imgproc.warpAffine
import org.bytedeco.opencv.global.opencv_video.calcOpticalFlowPyrLK
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class Point(val x: Double, val y: Double)

/** Similarity transform `x' = a·x − b·y + tx, y' = b·x + a·y + ty`, in analysis pixels. */
internal data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {
    fun apply(p: Point) = Point(a * p.x - b * p.y + tx, b * p.x + a * p.y + ty)

    /** `this ∘ inner`: applies [inner] first. */
    fun compose(inner: Similarity) = Similarity(
        a = a * inner.a - b * inner.b,
        b = b * inner.a + a * inner.b,
        tx = a * inner.tx - b * inner.ty + tx,
        ty = b * inner.tx + a * inner.ty + ty,
    )
}

/** Pose of a tracked element relative to its reference frame, measured at [Track.pivot]. */
internal data class ElementPose(val dx: Double, val dy: Double, val scale: Double, val rotationDegrees: Double)

internal class Track(
    /** Moving feature points in the reference frame. */
    val referencePoints: List<Point>,
    /** Transform from the reference frame per frame (null where it could not be estimated). */
    val transforms: List<Similarity?>,
) {
    val minX = referencePoints.minOf { it.x }
    val maxX = referencePoints.maxOf { it.x }
    val minY = referencePoints.minOf { it.y }
    val maxY = referencePoints.maxOf { it.y }

    /**
     * Point the rotation/scale turns about. A similarity with rotation or scale has exactly one
     * fixed point; when it lies on (or near) the element the motion is a pure rotate/scale about
     * it. Otherwise the element also moves, and the centre of its bounds is used, as in Compose.
     */
    val pivot: Point = run {
        val center = Point((minX + maxX) / 2, (minY + maxY) / 2)
        val t = transforms.lastOrNull() ?: return@run center
        val det = (1 - t.a) * (1 - t.a) + t.b * t.b
        if (det < MIN_DETERMINANT) return@run center
        val fixed = Point(((1 - t.a) * t.tx - t.b * t.ty) / det, (t.b * t.tx + (1 - t.a) * t.ty) / det)
        val marginX = (maxX - minX) * PIVOT_MARGIN
        val marginY = (maxY - minY) * PIVOT_MARGIN
        val near = fixed.x in (minX - marginX)..(maxX + marginX) && fixed.y in (minY - marginY)..(maxY + marginY)
        if (near) fixed else center
    }

    val poses: List<ElementPose?> = transforms.map { t ->
        t?.let {
            val moved = it.apply(pivot)
            ElementPose(moved.x - pivot.x, moved.y - pivot.y, hypot(it.a, it.b), Math.toDegrees(atan2(it.b, it.a)))
        }
    }

    fun reversed() = Track(referencePoints, transforms.reversed())

    private companion object {
        /** Below this the transform is (almost) a pure translation and has no meaningful fixed point. */
        const val MIN_DETERMINANT = 1e-4
        const val PIVOT_MARGIN = 0.25
    }
}

/**
 * Follows corner features of one element with pyramidal Lucas–Kanade optical flow and fits a
 * similarity transform per frame. Points are kept only if they move, survive a forward–backward
 * check, and agree with the fitted transforms; the result is kept only if warping the reference
 * frame with the final transform explains the final frame (a fade does not).
 */
internal object FeatureTracker {

    private const val MAX_CORNERS = 300
    private const val QUALITY = 0.01
    private const val MIN_DISTANCE = 3.0
    private const val MIN_MOVE_PX = 0.5
    private const val MAX_ROUND_TRIP_PX = 0.5
    /** Points are outliers above max(MIN_RESIDUAL_PX, RESIDUAL_MEDIANS × median residual). */
    private const val MIN_RESIDUAL_PX = 1.5
    private const val RESIDUAL_MEDIANS = 3.0
    private const val MIN_POINTS = 3
    private const val REFINE_ITERATIONS = 2

    /** Warped reference must leave at most this share of the unexplained difference. */
    private const val MAX_UNEXPLAINED = 0.35

    /** Tracks from `frames.first()` through the rest; null when no moving element explains the change. */
    fun track(frames: List<Mat>, mask: Mat): Track? {
        val start = detect(frames.first(), mask)
        if (start.size < MIN_POINTS) return null
        val paths = follow(frames, start).filterNotNull()

        val moving = paths.filter { path -> path.any { distance(it, path.first()) > MIN_MOVE_PX } }
        if (moving.size < MIN_POINTS) return null
        val returned = follow(frames.reversed(), moving.map { it.last() })
        val consistent = moving.filterIndexed { i, path ->
            returned[i]?.let { distance(it.last(), path.first()) <= MAX_ROUND_TRIP_PX } ?: false
        }
        if (consistent.size < MIN_POINTS) return null

        // Lucas–Kanade assumes each window only shifts, so points drift slightly on elements that
        // scale or rotate; outliers are judged against the typical residual rather than a fixed one.
        val firstFit = fit(consistent)
        val residuals = consistent.map { path ->
            path.indices.maxOf { frame -> firstFit[frame]?.let { distance(it.apply(path.first()), path[frame]) } ?: 0.0 }
        }
        val limit = max(MIN_RESIDUAL_PX, RESIDUAL_MEDIANS * residuals.sorted()[residuals.size / 2])
        val inliers = consistent.filterIndexed { i, _ -> residuals[i] <= limit }
        if (inliers.size < MIN_POINTS) return null
        val reference = inliers.map { it.first() }
        val refined = fit(inliers).mapIndexed { frame, t -> t?.let { refine(frames.first(), frames[frame], reference, it) } }
        val track = Track(reference, refined)
        return track.takeIf { explainsChange(frames.first(), frames.last(), track) }
    }

    /**
     * Frame-to-frame flow drifts on rotating content. Removes the drift by un-warping [frame] with
     * the current estimate, measuring the small remaining motion against [reference] directly, and
     * composing the correction.
     */
    private fun refine(reference: Mat, frame: Mat, points: List<Point>, estimate: Similarity): Similarity {
        var transform = estimate
        repeat(REFINE_ITERATIONS) {
            val correction = Mat().use { aligned ->
                affineMat(transform).use { m ->
                    Scalar(0.0).use { border -> warpAffine(frame, aligned, m, frame.size(), INTER_LINEAR or WARP_INVERSE_MAP, BORDER_CONSTANT, border) }
                }
                val (moved, found) = flow(reference, aligned, points)
                val ok = points.indices.filter { found[it] }
                if (ok.size < MIN_POINTS) return transform
                similarity(ok.map { points[it] }, ok.map { moved[it] }) ?: return transform
            }
            transform = transform.compose(correction)
        }
        return transform
    }

    private fun detect(frame: Mat, mask: Mat): List<Point> =
        Mat().use { corners ->
            goodFeaturesToTrack(frame, corners, MAX_CORNERS, QUALITY, MIN_DISTANCE, mask, 3, false, 0.04)
            if (corners.rows() == 0) return emptyList()
            corners.createIndexer<FloatIndexer>().use { idx ->
                (0 until corners.rows()).map { Point(idx.get(it.toLong(), 0, 0).toDouble(), idx.get(it.toLong(), 0, 1).toDouble()) }
            }
        }

    /** Path of each start point through all frames, aligned with [start]; null where the point was lost. */
    private fun follow(frames: List<Mat>, start: List<Point>): List<List<Point>?> {
        val paths: MutableList<MutableList<Point>?> = start.mapTo(mutableListOf()) { mutableListOf(it) }
        for (i in 1 until frames.size) {
            val alive = paths.indices.filter { paths[it] != null }
            if (alive.isEmpty()) break
            val (next, found) = flow(frames[i - 1], frames[i], alive.map { paths[it]!!.last() })
            alive.forEachIndexed { j, index -> if (found[j]) paths[index]!! += next[j] else paths[index] = null }
        }
        return paths
    }

    private fun flow(from: Mat, to: Mat, points: List<Point>): Pair<List<Point>, List<Boolean>> =
        pointsMat(points).use { previous ->
            Mat().use { next ->
                Mat().use { status ->
                    Mat().use { error ->
                        calcOpticalFlowPyrLK(from, to, previous, next, status, error)
                        val found = status.createIndexer<UByteIndexer>().use { idx -> points.indices.map { idx.get(it.toLong()) == 1 } }
                        val moved = next.createIndexer<FloatIndexer>().use { idx ->
                            points.indices.map { Point(idx.get(it.toLong(), 0, 0).toDouble(), idx.get(it.toLong(), 0, 1).toDouble()) }
                        }
                        moved to found
                    }
                }
            }
        }

    private fun fit(paths: List<List<Point>>): List<Similarity?> =
        paths.first().indices.map { frame -> similarity(paths.map { it.first() }, paths.map { it[frame] }) }

    private fun similarity(from: List<Point>, to: List<Point>): Similarity? =
        pointsMat(from).use { src ->
            pointsMat(to).use { dst ->
                estimateAffinePartial2D(src, dst).use { m ->
                    if (m.empty()) return null
                    m.createIndexer<DoubleIndexer>().use { idx -> Similarity(idx.get(0, 0), idx.get(1, 0), idx.get(0, 2), idx.get(1, 2)) }
                }
            }
        }

    /**
     * Compares, inside the element's final bounds, how far the final frame is from the reference
     * frame warped by the final transform versus from the unwarped reference frame.
     */
    private fun explainsChange(reference: Mat, final: Mat, track: Track): Boolean {
        val transform = track.transforms.last() ?: return false
        val corners = track.referencePoints.map(transform::apply)
        val x0 = max(0.0, floor(corners.minOf { it.x })).toInt()
        val y0 = max(0.0, floor(corners.minOf { it.y })).toInt()
        val x1 = min(final.cols().toDouble(), corners.maxOf { it.x } + 1).toInt()
        val y1 = min(final.rows().toDouble(), corners.maxOf { it.y } + 1).toInt()
        if (x1 - x0 < 2 || y1 - y0 < 2) return false
        Rect(x0, y0, x1 - x0, y1 - y0).use { box ->
            Mat().use { warped ->
                affineMat(transform).use { m -> warpAffine(reference, warped, m, reference.size()) }
                val unexplained = meanAbsDiff(warped, final, box)
                val change = meanAbsDiff(reference, final, box)
                return change > 1.0 && unexplained <= change * MAX_UNEXPLAINED
            }
        }
    }

    private fun meanAbsDiff(a: Mat, b: Mat, box: Rect): Double =
        Mat(a, box).use { ra ->
            Mat(b, box).use { rb ->
                Mat().use { diff ->
                    absdiff(ra, rb, diff)
                    mean(diff).get(0)
                }
            }
        }

    private fun affineMat(t: Similarity): Mat = Mat(2, 3, CV_64F).also { m ->
        m.createIndexer<DoubleIndexer>().use { idx ->
            idx.put(0, 0, t.a); idx.put(0, 1, -t.b); idx.put(0, 2, t.tx)
            idx.put(1, 0, t.b); idx.put(1, 1, t.a); idx.put(1, 2, t.ty)
        }
    }

    private fun pointsMat(points: List<Point>): Mat = Mat(points.size, 1, CV_32FC2).also { mat ->
        mat.createIndexer<FloatIndexer>().use { idx ->
            points.forEachIndexed { i, p ->
                idx.put(i.toLong(), 0, 0, p.x.toFloat())
                idx.put(i.toLong(), 0, 1, p.y.toFloat())
            }
        }
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
