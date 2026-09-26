package dev.visuals.blueprint.motion

import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoded video, downscaled for analysis. [colour] and [gray] are at analysis size; multiply
 * analysis coordinates by [pixelScale] to get video pixels.
 */
internal class DecodedVideo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val pixelScale: Double,
    /**
     * Presentation time of each frame in milliseconds, 0 at the first frame. Screen recordings often
     * have variable frame rates, so frame indices are not a clock.
     */
    val timesMs: DoubleArray,
    val colour: List<Mat>,
    val gray: List<Mat>,
) : AutoCloseable {

    val frameCount: Int get() = timesMs.size

    override fun close() {
        colour.forEach(Mat::close)
        gray.forEach(Mat::close)
    }
}

internal object VideoReader {

    fun read(file: File, maxAnalysisSize: Int): DecodedVideo {
        av_log_set_level(AV_LOG_ERROR)
        val converter = OpenCVFrameConverter.ToMat()
        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            val width = grabber.imageWidth
            val height = grabber.imageHeight
            val pixelScale = max(1.0, max(width, height).toDouble() / maxAnalysisSize)
            val size = Size((width / pixelScale).roundToInt(), (height / pixelScale).roundToInt())
            val times = mutableListOf<Double>()
            val colour = mutableListOf<Mat>()
            val gray = mutableListOf<Mat>()
            while (true) {
                val frame = grabber.grabImage() ?: break
                val bgr = converter.convert(frame) ?: continue
                val scaled = Mat().also { resize(bgr, it, size, 0.0, 0.0, INTER_AREA) }
                gray += Mat().also { cvtColor(scaled, it, COLOR_BGR2GRAY) }
                colour += scaled
                times += grabber.timestamp / 1000.0
            }
            size.close()
            check(times.size >= 2) { "Video has fewer than two frames: $file" }
            val first = times.first()
            val fromStart = DoubleArray(times.size) { times[it] - first }
            return DecodedVideo(width, height, grabber.frameRate, pixelScale, fromStart, colour, gray)
        }
    }
}
