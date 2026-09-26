package dev.visuals.blueprint.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wireframe + navigation blueprint extracted from one APK. */
@Serializable
data class Blueprint(
    val schemaVersion: Int = SCHEMA_VERSION,
    val source: Source,
    val app: AppInfo,
    val ui: UiToolkit,
    val screens: List<Screen>,
    val navigation: List<NavEdge>,
    val navGraphs: List<NavGraph>,
    val layouts: Map<String, WireframeNode>,
    val warnings: List<String>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class Source(val file: String, val sha256: String)

@Serializable
data class AppInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val label: String?,
)

@Serializable
data class UiToolkit(
    /** App XML layouts were found. */
    val views: Boolean,
    /** Jetpack Compose libraries are bundled; says nothing about which screens use them. */
    val compose: Boolean,
)

@Serializable
data class Screen(
    /** Fully qualified class name, or route key for Compose destinations. */
    val id: String,
    val kind: ScreenKind,
    val launcher: Boolean = false,
    val label: String? = null,
    val layouts: List<LayoutBinding> = emptyList(),
)

@Serializable
enum class ScreenKind {
    @SerialName("activity") ACTIVITY,
    @SerialName("fragment") FRAGMENT,
    @SerialName("dialog") DIALOG,
    @SerialName("compose-route") COMPOSE_ROUTE,
}

@Serializable
data class LayoutBinding(val layout: String, val how: BindingKind, val evidence: String)

@Serializable
enum class BindingKind {
    @SerialName("setContentView") SET_CONTENT_VIEW,
    @SerialName("inflate") INFLATE,
    @SerialName("constructor") CONSTRUCTOR,
    @SerialName("view-binding") VIEW_BINDING,
    @SerialName("nav-graph") NAV_GRAPH,
    @SerialName("name-convention") NAME_CONVENTION,
}

@Serializable
data class NavEdge(
    /** Source screen id, or null when the call site could not be tied to a screen. */
    val from: String?,
    val to: String,
    val via: NavVia,
    val confidence: Confidence,
    val evidence: String,
)

@Serializable
enum class NavVia {
    @SerialName("intent") INTENT,
    @SerialName("nav-action") NAV_ACTION,
    @SerialName("nav-global-action") NAV_GLOBAL_ACTION,
    @SerialName("nav-graph-start") NAV_GRAPH_START,
    @SerialName("hosts-fragment") HOSTS_FRAGMENT,
    @SerialName("compose-route") COMPOSE_ROUTE,
}

@Serializable
enum class Confidence {
    /** Declared in a resource (manifest, navigation graph, layout). */
    @SerialName("declared") DECLARED,
    /** Found in code of the source screen itself. */
    @SerialName("direct") DIRECT,
    /** Found in a helper class that exactly one screen uses. */
    @SerialName("inferred") INFERRED,
    /** Found in code, but the source screen is unknown. */
    @SerialName("unresolved") UNRESOLVED,
}

@Serializable
data class NavGraph(
    val name: String,
    val startDestination: String?,
    val destinations: List<NavDestination>,
    val actions: List<NavAction>,
)

@Serializable
data class NavDestination(val id: String, val kind: ScreenKind?, val className: String?, val label: String?)

@Serializable
data class NavAction(val id: String, val from: String?, val to: String)

@Serializable
data class WireframeNode(
    val type: String,
    val role: Role,
    val id: String? = null,
    val text: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val src: String? = null,
    val width: String? = null,
    val height: String? = null,
    val orientation: String? = null,
    val visibility: String? = null,
    /** Layout pulled in by `<include>`. */
    val include: String? = null,
    /** Fragment class hosted by `<fragment>` / `FragmentContainerView`. */
    val fragment: String? = null,
    /** Navigation graph attached to a NavHostFragment. */
    val navGraph: String? = null,
    val children: List<WireframeNode> = emptyList(),
)

@Serializable
enum class Role {
    @SerialName("container") CONTAINER,
    @SerialName("scroll") SCROLL,
    @SerialName("text") TEXT,
    @SerialName("button") BUTTON,
    @SerialName("fab") FAB,
    @SerialName("image") IMAGE,
    @SerialName("input") INPUT,
    @SerialName("list") LIST,
    @SerialName("pager") PAGER,
    @SerialName("toggle") TOGGLE,
    @SerialName("chip") CHIP,
    @SerialName("progress") PROGRESS,
    @SerialName("slider") SLIDER,
    @SerialName("toolbar") TOOLBAR,
    @SerialName("navigation") NAVIGATION,
    @SerialName("card") CARD,
    @SerialName("web") WEB,
    @SerialName("divider") DIVIDER,
    @SerialName("space") SPACE,
    @SerialName("fragment-host") FRAGMENT_HOST,
    @SerialName("compose-host") COMPOSE_HOST,
    @SerialName("include") INCLUDE,
    @SerialName("view") VIEW,
    @SerialName("custom") CUSTOM,
}
