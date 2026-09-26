package dev.visuals.blueprint

import dev.visuals.blueprint.apk.ApkExtractor
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  blueprint apk <file.apk> [-o <dir>]   Extract the wireframe + navigation blueprint of an APK.
                                        Writes <dir>/blueprint.json and <dir>/navigation.mmd
                                        (default <dir>: ./<apk name>-blueprint)."""

private val json = Json {
    prettyPrint = true
    explicitNulls = false
}

fun main(args: Array<String>) {
    val command = args.firstOrNull()
    when (command) {
        "apk" -> runApk(args.drop(1))
        "-h", "--help", null -> println(USAGE)
        else -> fail("Unknown command: $command")
    }
}

private fun runApk(args: List<String>) {
    val apk = args.firstOrNull { !it.startsWith("-") }?.let(::File) ?: fail("Missing <file.apk>")
    val outIndex = args.indexOf("-o")
    val outDir = if (outIndex >= 0) {
        File(args.getOrNull(outIndex + 1) ?: fail("Missing directory after -o"))
    } else {
        File("${apk.nameWithoutExtension}-blueprint")
    }
    if (!apk.isFile) fail("APK not found: $apk")

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

private fun fail(message: String): Nothing {
    System.err.println(message)
    System.err.println(USAGE)
    exitProcess(2)
}
