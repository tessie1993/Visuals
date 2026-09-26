package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.AppInfo
import dev.visuals.blueprint.model.BindingKind
import dev.visuals.blueprint.model.Blueprint
import dev.visuals.blueprint.model.Confidence
import dev.visuals.blueprint.model.LayoutBinding
import dev.visuals.blueprint.model.NavAction
import dev.visuals.blueprint.model.NavDestination
import dev.visuals.blueprint.model.NavEdge
import dev.visuals.blueprint.model.NavGraph
import dev.visuals.blueprint.model.NavVia
import dev.visuals.blueprint.model.Role
import dev.visuals.blueprint.model.ScreenKind
import dev.visuals.blueprint.model.Source
import dev.visuals.blueprint.model.WireframeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintAssemblerTest {

    private val layouts = mapOf(
        "activity_main" to WireframeNode(
            type = "FrameLayout",
            role = Role.CONTAINER,
            children = listOf(
                WireframeNode(
                    type = "androidx.fragment.app.FragmentContainerView",
                    role = Role.FRAGMENT_HOST,
                    fragment = "androidx.navigation.fragment.NavHostFragment",
                    navGraph = "main_graph",
                ),
            ),
        ),
        "activity_detail" to WireframeNode(type = "LinearLayout", role = Role.CONTAINER),
        "activity_settings" to WireframeNode(type = "LinearLayout", role = Role.CONTAINER),
        "fragment_home" to WireframeNode(type = "LinearLayout", role = Role.CONTAINER),
        "dialog_confirm" to WireframeNode(type = "LinearLayout", role = Role.CONTAINER),
    )

    private fun scan(className: String, bindings: List<CodeBinding> = emptyList(), intents: List<CodeRef> = emptyList()) =
        ClassScan(className, bindings, bindings.map { it.layout }.toSet(), intents, emptyList(), emptyList(), emptyList())

    private val blueprint: Blueprint = BlueprintAssembler(
        ApkFacts(
            manifest = Manifest(
                app = AppInfo("com.example", "1.0", "1", "24", "36", "Example"),
                activities = listOf(
                    ManifestActivity("com.example.MainActivity", label = null, launcher = true),
                    ManifestActivity("com.example.DetailActivity", label = null, launcher = false),
                    ManifestActivity("com.example.SettingsActivity", label = null, launcher = false),
                ),
            ),
            navGraphs = listOf(
                NavGraph(
                    name = "main_graph",
                    startDestination = "home",
                    destinations = listOf(
                        NavDestination("home", ScreenKind.FRAGMENT, "com.example.HomeFragment", "Home"),
                        NavDestination("detail", ScreenKind.ACTIVITY, "com.example.DetailActivity", null),
                    ),
                    actions = listOf(NavAction("toDetail", "home", "detail")),
                ),
            ),
            layoutNames = layouts.keys,
            parseLayout = layouts::getValue,
            scans = listOf(
                scan(
                    "com.example.MainActivity",
                    bindings = listOf(CodeBinding(null, "activity_main", BindingKind.SET_CONTENT_VIEW, 10)),
                    intents = listOf(CodeRef("SettingsActivity", 20)),
                ),
                scan("com.example.HomeFragment", bindings = listOf(CodeBinding(null, "fragment_home", BindingKind.CONSTRUCTOR, 5))),
                scan("com.example.DetailActivity", bindings = listOf(CodeBinding(null, "dialog_confirm", BindingKind.INFLATE, 30))),
                scan("a.b", intents = listOf(CodeRef("DetailActivity", 3, context = "settingsActivity"))),
                scan("a.c", intents = listOf(CodeRef("SettingsActivity", 4))),
                scan("a.d", intents = listOf(CodeRef("MainActivity", 5))),
            ),
            classNames = setOf("com.example.MainActivity", "com.example.DetailActivity", "com.example.SettingsActivity", "com.example.HomeFragment"),
            routeKeyClasses = emptySet(),
            usersOf = { name -> if (name == "a.c") setOf("com.example.DetailActivity") else emptySet() },
            composeDetected = false,
            obfuscated = false,
        ),
    ).assemble(Source("example.apk", "0"))

    private fun screen(id: String) = blueprint.screens.single { it.id == id }

    @Test
    fun `collects screens from the manifest and navigation graphs`() {
        assertEquals(
            listOf(
                "com.example.MainActivity" to ScreenKind.ACTIVITY,
                "com.example.DetailActivity" to ScreenKind.ACTIVITY,
                "com.example.SettingsActivity" to ScreenKind.ACTIVITY,
                "com.example.HomeFragment" to ScreenKind.FRAGMENT,
            ),
            blueprint.screens.map { it.id to it.kind },
        )
        assertTrue(screen("com.example.MainActivity").launcher)
    }

    @Test
    fun `binds layouts from code and falls back to the naming convention`() {
        assertEquals(
            listOf(LayoutBinding("activity_main", BindingKind.SET_CONTENT_VIEW, "com.example.MainActivity:10")),
            screen("com.example.MainActivity").layouts,
        )
        assertEquals(
            listOf("activity_detail" to BindingKind.NAME_CONVENTION, "dialog_confirm" to BindingKind.INFLATE),
            screen("com.example.DetailActivity").layouts.map { it.layout to it.how },
        )
        assertEquals(listOf("activity_settings"), screen("com.example.SettingsActivity").layouts.map { it.layout })
        assertEquals(listOf("fragment_home"), screen("com.example.HomeFragment").layouts.map { it.layout })
    }

    @Test
    fun `records every edge with its source and confidence`() {
        val main = "com.example.MainActivity"
        val detail = "com.example.DetailActivity"
        val settings = "com.example.SettingsActivity"
        val home = "com.example.HomeFragment"
        assertEquals(
            setOf(
                NavEdge(home, detail, NavVia.NAV_ACTION, Confidence.DECLARED, "navigation/main_graph#toDetail"),
                NavEdge(main, home, NavVia.NAV_GRAPH_START, Confidence.DECLARED, "layout/activity_main → navigation/main_graph"),
                NavEdge(main, settings, NavVia.INTENT, Confidence.DIRECT, "com.example.MainActivity:20"),
                NavEdge(settings, detail, NavVia.INTENT, Confidence.INFERRED, "a.b:3 (context: settingsActivity)"),
                NavEdge(detail, settings, NavVia.INTENT, Confidence.INFERRED, "a.c:4"),
                NavEdge(null, main, NavVia.INTENT, Confidence.UNRESOLVED, "a.d:5"),
            ),
            blueprint.navigation.toSet(),
        )
    }

    @Test
    fun `includes bound and referenced layouts and reports unresolved edges`() {
        assertEquals(layouts.keys.sorted(), blueprint.layouts.keys.toList())
        assertEquals(listOf("1 navigation edges have an unknown source screen (from = null)."), blueprint.warnings)
    }
}
