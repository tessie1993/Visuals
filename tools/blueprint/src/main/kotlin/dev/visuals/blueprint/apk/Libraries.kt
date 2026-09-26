package dev.visuals.blueprint.apk

/** Known third-party code and resources, excluded so the blueprint describes the app itself. */
internal object Libraries {

    private val CLASS_PREFIXES = listOf(
        "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.",
        "com.google.android.", "com.google.common.", "com.google.firebase.", "com.google.gson.",
        "com.google.protobuf.", "com.google.errorprone.", "com.google.accompanist.",
        "com.squareup.", "okhttp3.", "okio.", "retrofit2.", "dagger.", "hilt_aggregated_deps.",
        "io.reactivex.", "io.ktor.", "coil.", "coil3.", "com.bumptech.", "org.intellij.",
        "org.jetbrains.", "org.slf4j.",
    )

    private val LAYOUT_PREFIXES = listOf(
        "abc_", "mtrl_", "design_", "material_", "m3_", "notification_", "select_dialog_",
        "support_simple_", "browser_actions_", "preference", "exo_", "leak_canary_", "com_facebook_",
    )

    fun isLibraryClass(name: String, appPackage: String): Boolean =
        !name.startsWith("$appPackage.") && CLASS_PREFIXES.any { name.startsWith(it) }

    fun isLibraryLayout(name: String): Boolean = LAYOUT_PREFIXES.any { name.startsWith(it) }
}
