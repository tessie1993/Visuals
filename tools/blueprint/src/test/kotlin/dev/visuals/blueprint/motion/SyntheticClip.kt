package dev.visuals.blueprint.motion

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

/** Writes lossless test clips of a card animated with known motion on a static screen. */
internal object SyntheticClip {

    const val WIDTH = 360
    const val HEIGHT = 640
    const val FPS = 60.0
    private const val CARD_WIDTH = 120.0
    private const val CARD_HEIGHT = 80.0

    /** Card state at a time: centre, scale and rotation (degrees) about the centre, opacity. */
    data class Card(val cx: Double, val cy: Double, val scale: Double = 1.0, val rotation: Double = 0.0, val alpha: Double = 1.0)

    fun write(file: File, durationMs: Double, card: (timeMs: Double) -> Card) {
        val converter = Java2DFrameConverter()
        FFmpegFrameRecorder(file, WIDTH, HEIGHT).use { recorder ->
            recorder.format = "matroska"
            recorder.videoCodec = avcodec.AV_CODEC_ID_FFV1
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV444P
            recorder.frameRate = FPS
            recorder.start()
            val frames = (durationMs / 1000 * FPS).roundToInt()
            for (frame in 0 until frames) {
                val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR)
                image.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    drawScreen()
                    drawCard(card(frame * 1000 / FPS))
                    dispose()
                }
                recorder.record(converter.convert(image))
            }
            recorder.stop()
        }
    }

    /** Static background with detail, so tracking has to ignore features that do not move. */
    private fun Graphics2D.drawScreen() {
        color = Color(0xF2, 0xF2, 0xF2)
        fillRect(0, 0, WIDTH, HEIGHT)
        color = Color(0x9E, 0x9E, 0x9E)
        for (row in 0 until 12) fillRect(24, 40 + row * 50, 60 + (row * 37) % 200, 8)
    }

    private fun Graphics2D.drawCard(card: Card) {
        val saved = transform
        val savedComposite = composite
        composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, card.alpha.toFloat())
        transform(AffineTransform().apply {
            translate(card.cx, card.cy)
            rotate(Math.toRadians(card.rotation))
            scale(card.scale, card.scale)
        })
        color = Color(0x3F, 0x51, 0xB5)
        fill(RoundRectangle2D.Double(-CARD_WIDTH / 2, -CARD_HEIGHT / 2, CARD_WIDTH, CARD_HEIGHT, 16.0, 16.0))
        color = Color.WHITE
        fillRect(-44, -24, 20, 20)
        fillRect(24, -24, 20, 20)
        fillRect(-44, 4, 88, 20)
        transform = saved
        composite = savedComposite
    }
}
