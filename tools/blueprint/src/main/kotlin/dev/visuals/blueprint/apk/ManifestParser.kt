package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.AppInfo
import org.w3c.dom.Element

internal data class ManifestActivity(val name: String, val label: String?, val launcher: Boolean)

internal data class Manifest(val app: AppInfo, val activities: List<ManifestActivity>)

internal object ManifestParser {

    fun parse(xml: String, strings: Map<String, String>): Manifest {
        val root = parseXml(xml)
        val pkg = checkNotNull(root.plainAttr("package")) { "AndroidManifest.xml has no package attribute" }
        val usesSdk = root.childElements.firstOrNull { it.tagName == "uses-sdk" }
        val application = root.childElements.firstOrNull { it.tagName == "application" }
        val components = application?.childElements.orEmpty()

        val aliasLaunchTargets = components
            .filter { it.tagName == "activity-alias" && it.isLauncher() }
            .mapNotNull { it.androidAttr("targetActivity")?.let { target -> qualify(pkg, target) } }
            .toSet()

        val activities = components.filter { it.tagName == "activity" }.map { activity ->
            val name = qualify(pkg, checkNotNull(activity.androidAttr("name")))
            ManifestActivity(
                name = name,
                label = resolveString(activity.androidAttr("label"), strings),
                launcher = activity.isLauncher() || name in aliasLaunchTargets,
            )
        }

        val app = AppInfo(
            packageName = pkg,
            versionName = root.androidAttr("versionName"),
            versionCode = root.androidAttr("versionCode"),
            minSdk = usesSdk?.androidAttr("minSdkVersion"),
            targetSdk = usesSdk?.androidAttr("targetSdkVersion"),
            label = resolveString(application?.androidAttr("label"), strings),
        )
        return Manifest(app, activities)
    }

    private fun Element.isLauncher(): Boolean = childElements
        .filter { it.tagName == "intent-filter" }
        .any { filter ->
            val children = filter.childElements
            children.any { it.tagName == "action" && it.androidAttr("name") == "android.intent.action.MAIN" } &&
                children.any { it.tagName == "category" && it.androidAttr("name") == "android.intent.category.LAUNCHER" }
        }

    /** Manifest class names may be relative to the package (".Foo" or "Foo"). */
    fun qualify(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        '.' !in name -> "$pkg.$name"
        else -> name
    }
}

/** Resolves `@string/name` to its default value; other values are returned unchanged. */
internal fun resolveString(value: String?, strings: Map<String, String>): String? =
    resourceName(value, "string")?.let { strings[it] ?: value } ?: value
