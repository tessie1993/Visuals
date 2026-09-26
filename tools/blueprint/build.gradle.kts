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

dependencies {
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
