package dev.visuals.blueprint

import dev.visuals.blueprint.apk.ApkExtractor
import dev.visuals.blueprint.motion.ComposeWriter
import dev.visuals.blueprint.motion.LottieWriter
import dev.visuals.blueprint.motion.MotionAnalyzer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  blueprint apk <file.apk> [-o <dir>]
      Extract the wireframe + navigation blueprint of an APK.
      Writes <dir>/blueprint.json and <dir>/navigation.mmd (default <dir>: ./<apk name>-blueprint).

  blueprint motion <video> [-o <dir>] [--px-per-dp <n>] [--package <name>]
      Measure UI motion in a video (e.g. a screen recording).
      Writes <dir>/motion.json, <dir>/Motion.kt and <dir>/motion.lottie.json
      (default <dir>: ./<video name>-motion). --px-per-dp converts video pixels to dp in
      Motion.kt, e.g. 2.625 for a 420 dpi recording (default 1). --package defaults to "motion"."""

private val json = Json {
    prettyPrint = true
    explicitNulls = false
}

fun main(args: Array<String>) {
    // Route JavaCPP's own messages through SLF4J (bound to a no-op logger) instead of stderr.
    System.setProperty("org.bytedeco.javacpp.logger", "slf4j")
    when (val command = args.firstOrNull()) {
        "apk" -> runApk(Args(args.drop(1)))
        "motion" -> runMotion(Args(args.drop(1)))
        "-h", "--help", null -> println(USAGE)
        else -> fail("Unknown command: $command")
    }
}

/** `<input> [--option value]...` */
private class Args(private val values: List<String>) {
    private val options = values.indices.filter { values[it].startsWith("-") }.associate { values[it] to values.getOrNull(it + 1) }

    val input: File = values.filterIndexed { i, v -> !v.startsWith("-") && values.getOrNull(i - 1)?.startsWith("-") != true }
        .firstOrNull()?.let(::File) ?: fail("Missing input file")

    fun option(name: String): String? = if (name in options) options[name] ?: fail("Missing value after $name") else null
}

private fun runApk(args: Args) {
    val apk = args.input
    if (!apk.isFile) fail("APK not found: $apk")
    val outDir = File(args.option("-o") ?: "${apk.nameWithoutExtension}-blueprint")

    val blueprint = ApkExtractor(log = { System.err.println(it) }).extract(apk)
    outDir.mkdirs()
    val jsonFile = File(outDir, "blueprint.json").apply { writeText(json.encodeToString(blueprint) + "\n") }
    val mermaidFile = File(outDir, "navigation.mmd").apply { writeText(MermaidWriter.write(blueprint)) }

    println("${blueprint.app.packageName} ${blueprint.app.versionName.orEmpty()}")
    println("  screens:    ${blueprint.screens.size}")
    println("  navigation: ${blueprint.navigation.size} edges")
    println("  layouts:    ${blueprint.layouts.size}")
    blueprint.warnings.forEach { println("  warning:    $it") }
    println("Wrote ${jsonFile.path}")
    println("Wrote ${mermaidFile.path}")
}

private fun runMotion(args: Args) {
    val video = args.input
    if (!video.isFile) fail("Video not found: $video")
    val outDir = File(args.option("-o") ?: "${video.nameWithoutExtension}-motion")
    val pxPerDp = args.option("--px-per-dp")?.let { it.toDoubleOrNull()?.takeIf { v -> v > 0 } ?: fail("--px-per-dp must be a positive number") } ?: 1.0
    val packageName = args.option("--package") ?: "motion"

    val spec = MotionAnalyzer(log = { System.err.println(it) }).analyze(video)
    outDir.mkdirs()
    val specFile = File(outDir, "motion.json").apply { writeText(json.encodeToString(spec) + "\n") }
    val composeFile = File(outDir, "Motion.kt").apply { writeText(ComposeWriter.write(spec, packageName, pxPerDp)) }
    val lottieFile = File(outDir, "motion.lottie.json").apply { writeText(json.encodeToString(LottieWriter.write(spec)) + "\n") }

    println("${video.name}: ${spec.video.width}x${spec.video.height}, ${spec.video.frames} frames")
    spec.elements.forEach { element ->
        println("  ${element.id} (${element.tracking.name.lowercase()}):")
        element.animations.forEach { a ->
            println("    ${a.property.name.lowercase()} ${a.from} -> ${a.to} ${a.property.unit}, ${a.durationMs} ms from ${a.startMs} ms, ${a.easing}")
        }
    }
    spec.warnings.forEach { println("  warning: $it") }
    println("Wrote ${specFile.path}")
    println("Wrote ${composeFile.path}")
    println("Wrote ${lottieFile.path}")
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    System.err.println(USAGE)
    exitProcess(2)
}
