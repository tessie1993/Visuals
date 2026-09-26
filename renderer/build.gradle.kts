plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.visuals.suite"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric 4.17 reads JDK internals on JDK 17+:
                // https://github.com/robolectric/robolectric/issues/11434
                test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // Render a single component when -Pvisuals.component=<Name> is passed.
                project.findProperty("visuals.component")?.let {
                    test.systemProperty("visuals.component", it)
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
