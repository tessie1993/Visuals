plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.visuals.blueprint.MainKt")
    applicationName = "blueprint"
}

/** JavaCPP classifier of the machine running the build, e.g. `linux-x86_64`; override with -PjavacppPlatform. */
val javacppPlatform: String = providers.gradleProperty("javacppPlatform").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported architecture ${System.getProperty("os.arch")}; pass -PjavacppPlatform")
    }
    when {
        os.startsWith("linux") -> "linux-$arch"
        os.startsWith("mac") -> "macosx-$arch"
        os.startsWith("windows") -> "windows-$arch"
        else -> error("Unsupported OS $os; pass -PjavacppPlatform")
    }
}

/** Camera and OCR backends JavaCV declares but `motion` never loads. */
val unusedJavacvModules = listOf(
    "artoolkitplus", "flycapture", "leptonica", "libdc1394", "libfreenect", "libfreenect2",
    "librealsense", "librealsense2", "tesseract", "videoinput",
)

dependencies {
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hipparchus.optim)
    implementation(libs.javacv) {
        unusedJavacvModules.forEach { exclude(group = "org.bytedeco", module = it) }
    }
    listOf(libs.javacpp, libs.ffmpeg, libs.opencv, libs.openblas).forEach { lib ->
        implementation(lib)
        runtimeOnly(variantOf(lib) { classifier(javacppPlatform) })
    }
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
