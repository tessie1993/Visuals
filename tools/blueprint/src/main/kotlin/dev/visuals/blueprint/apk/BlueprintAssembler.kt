package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.BindingKind
import dev.visuals.blueprint.model.Blueprint
import dev.visuals.blueprint.model.Confidence
import dev.visuals.blueprint.model.LayoutBinding
import dev.visuals.blueprint.model.NavEdge
import dev.visuals.blueprint.model.NavGraph
import dev.visuals.blueprint.model.NavVia
import dev.visuals.blueprint.model.Screen
import dev.visuals.blueprint.model.ScreenKind
import dev.visuals.blueprint.model.Source
import dev.visuals.blueprint.model.UiToolkit
import dev.visuals.blueprint.model.WireframeNode

/** Everything read from an APK, independent of the decompiler that produced it. */
internal data class ApkFacts(
    val manifest: Manifest,
    val navGraphs: List<NavGraph>,
    val layoutNames: Set<String>,
    val parseLayout: (String) -> WireframeNode,
    val scans: List<ClassScan>,
    /** Every class in the APK, including inner classes. */
    val classNames: Set<String>,
    /** Classes implementing Navigation 3's `NavKey`. */
    val routeKeyClasses: Set<String>,
    /** Top-level classes whose code uses the given class. */
    val usersOf: (String) -> Set<String>,
    val composeDetected: Boolean,
    val obfuscated: Boolean,
)

/** Turns [ApkFacts] into a [Blueprint]. Every edge records where it was found and how sure it is. */
internal class BlueprintAssembler(private val facts: ApkFacts) {

    private val pkg = facts.manifest.app.packageName
    private val screens = LinkedHashMap<String, ScreenDraft>()
    private val edges = LinkedHashMap<Triple<String?, String, NavVia>, NavEdge>()
    private val warnings = mutableListOf<String>()
    private val layoutCache = HashMap<String, WireframeNode?>()
    private val scansByClass = facts.scans.associateBy { it.className }
    private val layoutsByNormalizedName = facts.layoutNames.associateBy { it.replace("_", "") }

    private class ScreenDraft(val id: String, val kind: ScreenKind, var launcher: Boolean, var label: String?) {
        val bindings = mutableListOf<LayoutBinding>()
    }

    fun assemble(source: Source): Blueprint {
        addManifestActivities()
        addNavGraphDestinations()
        addCodeFragments()
        screens.values.forEach(::bindLayouts)
        addLayoutHostedFragments()
        addComposeRoutes()
        screens.values.filter { screen -> screen.bindings.all { it.how == BindingKind.INFLATE } }.forEach(::bindByNameConvention)

        addNavGraphEdges()
        addIntentEdges()
        addFragmentInstantiationEdges()
        addComposeRouteEdges()

        val layouts = collectLayouts()
        addSummaryWarnings()
        return Blueprint(
            schemaVersion = Blueprint.SCHEMA_VERSION,
            source = source,
            app = facts.manifest.app,
            ui = UiToolkit(views = layouts.isNotEmpty(), compose = facts.composeDetected),
            screens = screens.values.map { Screen(it.id, it.kind, it.launcher, it.label, it.bindings.toList()) },
            navigation = edges.values.toList(),
            navGraphs = facts.navGraphs,
            layouts = layouts,
            warnings = warnings,
        )
    }

    // --- screens ---

    private fun addScreen(id: String, kind: ScreenKind, label: String? = null, launcher: Boolean = false): ScreenDraft =
        screens.getOrPut(id) { ScreenDraft(id, kind, launcher, label) }.also { draft ->
            draft.launcher = draft.launcher || launcher
            if (draft.label == null) draft.label = label
        }

    private fun addManifestActivities() {
        facts.manifest.activities
            .filterNot { Libraries.isLibraryClass(it.name, pkg) }
            .forEach { addScreen(it.name, ScreenKind.ACTIVITY, it.label, it.launcher) }
    }

    private fun addNavGraphDestinations() {
        for (graph in facts.navGraphs) {
            for (destination in graph.destinations) {
                val kind = destination.kind ?: continue
                val className = destination.className?.let { ManifestParser.qualify(pkg, it) } ?: continue
                if (!Libraries.isLibraryClass(className, pkg)) addScreen(className, kind, destination.label)
            }
        }
    }

    /** App classes named `*Fragment` that bind a layout in their own code or are instantiated by app code. */
    private fun addCodeFragments() {
        val candidates = facts.scans.map { it.className }
            .filter { it.endsWith("Fragment") && !Libraries.isLibraryClass(it, pkg) }
        val instantiated = facts.scans
            .flatMap { scan -> scan.instantiations.mapNotNull { resolve(it.token, candidates, scan.className) } }
            .toSet()
        candidates
            .filter { it in instantiated || scansByClass[it]?.bindings.orEmpty().any { binding -> binding.owner == null } }
            .forEach { addScreen(it, if (it.endsWith("DialogFragment")) ScreenKind.DIALOG else ScreenKind.FRAGMENT) }
    }

    private fun addLayoutHostedFragments() {
        for (screen in screens.values.toList()) {
            for (binding in screen.bindings) {
                layoutNodes(binding.layout).forEach { node ->
                    val hosted = node.fragment?.let { ManifestParser.qualify(pkg, it) }
                    if (hosted != null && hosted in facts.classNames && !Libraries.isLibraryClass(hosted, pkg)) {
                        val fragment = addScreen(hosted, ScreenKind.FRAGMENT)
                        if (fragment.bindings.isEmpty()) bindLayouts(fragment)
                        addEdge(screen.id, hosted, NavVia.HOSTS_FRAGMENT, Confidence.DECLARED, "layout/${binding.layout}")
                    }
                    node.navGraph?.let { graphName -> graphStart(graphName) }?.let { start ->
                        addEdge(screen.id, start, NavVia.NAV_GRAPH_START, Confidence.DECLARED, "layout/${binding.layout} → navigation/${node.navGraph}")
                    }
                }
            }
        }
    }

    private fun addComposeRoutes() {
        facts.routeKeyClasses.forEach { addScreen(it, ScreenKind.COMPOSE_ROUTE) }
        facts.scans.flatMap { it.routeDeclarations }.forEach { ref ->
            val typed = resolve(ref.token, facts.classNames, site = null)
            addScreen(typed ?: ref.token, ScreenKind.COMPOSE_ROUTE)
        }
    }

    // --- layouts bound to screens ---

    private fun bindLayouts(screen: ScreenDraft) {
        val own = scansByClass[screen.id]?.bindings.orEmpty().filter { it.owner == null }
        val explicit = facts.scans.flatMap { scan ->
            scan.bindings.filter { it.owner != null && resolve(it.owner, screens.keys, scan.className) == screen.id }
                .map { scan.className to it }
        }
        val found = own.map { screen.id to it } + explicit
        found.sortedWith(
            compareBy<Pair<String, CodeBinding>> { (_, binding) -> if (binding.kind == BindingKind.INFLATE) 1 else 0 }
                .thenByDescending { (_, binding) -> nameOverlap(screen.id, binding.layout) },
        )
            .filter { (_, binding) -> binding.layout in facts.layoutNames }
            .distinctBy { (_, binding) -> binding.layout }
            .mapTo(screen.bindings) { (site, binding) -> LayoutBinding(binding.layout, binding.kind, "$site:${binding.line}") }
    }

    private fun bindByNameConvention(screen: ScreenDraft) {
        val simple = screen.id.substringAfterLast('.')
        val candidate = when (screen.kind) {
            ScreenKind.ACTIVITY -> simple.removeSuffix("Activity").takeIf { it != simple }?.let { "activity_" + CodeScanner.snakeCase(it) }
            ScreenKind.FRAGMENT -> simple.removeSuffix("Fragment").takeIf { it != simple }?.let { "fragment_" + CodeScanner.snakeCase(it) }
            else -> null
        } ?: return
        val layout = layoutsByNormalizedName[candidate.replace("_", "")] ?: return
        screen.bindings.removeAll { it.layout == layout }
        screen.bindings.add(0, LayoutBinding(layout, BindingKind.NAME_CONVENTION, "layout/$layout matches $simple"))
    }

    // --- navigation edges ---

    private fun addNavGraphEdges() {
        for (graph in facts.navGraphs) {
            val ids = graph.destinations.associate { it.id to destinationId(graph, it.id) }
            for (action in graph.actions) {
                val to = ids[action.to] ?: "${graph.name}#${action.to}"
                val evidence = "navigation/${graph.name}#${action.id}"
                if (action.from == null) {
                    addEdge(null, to, NavVia.NAV_GLOBAL_ACTION, Confidence.DECLARED, evidence)
                } else {
                    addEdge(ids[action.from] ?: "${graph.name}#${action.from}", to, NavVia.NAV_ACTION, Confidence.DECLARED, evidence)
                }
            }
        }
    }

    private fun addIntentEdges() {
        val activities = screens.values.filter { it.kind == ScreenKind.ACTIVITY }.map { it.id }
        for (scan in facts.scans) {
            for (ref in scan.intentTargets) {
                val target = resolve(ref.token, activities, scan.className) ?: continue
                addCodeEdge(scan, ref, target, NavVia.INTENT, contextScreen(ref.context))
            }
        }
    }

    private fun addFragmentInstantiationEdges() {
        val fragments = screens.values.filter { it.kind == ScreenKind.FRAGMENT || it.kind == ScreenKind.DIALOG }.map { it.id }
        for (scan in facts.scans) {
            for (ref in scan.instantiations) {
                val target = resolve(ref.token, fragments, scan.className) ?: continue
                if (target != scan.className) addCodeEdge(scan, ref, target, NavVia.HOSTS_FRAGMENT)
            }
        }
    }

    /**
     * Compose navigation usually runs in lambdas that jadx hoists out of the route's content, so the
     * source route is unknown unless the call sits in a route class itself. The hosting activity is
     * kept as evidence, not as the source.
     */
    private fun addComposeRouteEdges() {
        val routes = screens.values.filter { it.kind == ScreenKind.COMPOSE_ROUTE }.map { it.id }
        for (scan in facts.scans) {
            val site = scan.className
            val host = facts.usersOf(site).filter { it in screens && it != site }.singleOrNull()
            for (ref in scan.routeNavigations) {
                val target = ref.token.takeIf { it in routes } ?: resolve(ref.token, routes, site) ?: continue
                val evidence = "$site:${ref.line}" + (host?.let { " (hosted by $it)" } ?: "")
                if (site in routes) {
                    addEdge(site, target, NavVia.COMPOSE_ROUTE, Confidence.DIRECT, evidence)
                } else {
                    addEdge(null, target, NavVia.COMPOSE_ROUTE, Confidence.UNRESOLVED, evidence)
                }
            }
        }
    }

    /** The screen named by an Intent's context variable (`mainActivity` → `MainActivity`), if unique. */
    private fun contextScreen(context: String?): String? {
        val simple = context?.replaceFirstChar(Char::uppercaseChar) ?: return null
        return screens.keys.filter { it.substringAfterLast('.') == simple }.singleOrNull()
    }

    /**
     * Source screen, most reliable first: the code's own screen, the Intent's context variable,
     * then the only screen that uses the helper class. R8 merges lambdas across classes, so the
     * last rule is reported as [Confidence.INFERRED], never [Confidence.DIRECT].
     */
    private fun addCodeEdge(scan: ClassScan, ref: CodeRef, target: String, via: NavVia, contextScreen: String? = null) {
        val site = ownerOf(scan.className)
        val evidence = "${scan.className}:${ref.line}"
        if (site in screens) {
            addEdge(site, target, via, Confidence.DIRECT, evidence)
            return
        }
        if (contextScreen != null) {
            addEdge(contextScreen, target, via, Confidence.INFERRED, "$evidence (context: ${ref.context})")
            return
        }
        val callers = facts.usersOf(site).filter { it in screens && it != site }.toSet()
        if (callers.size == 1) {
            addEdge(callers.single(), target, via, Confidence.INFERRED, evidence)
        } else {
            addEdge(null, target, via, Confidence.UNRESOLVED, evidence)
        }
    }

    private fun addEdge(from: String?, to: String, via: NavVia, confidence: Confidence, evidence: String) {
        val key = Triple(from, to, via)
        val existing = edges[key]
        if (existing == null || confidence.ordinal < existing.confidence.ordinal) {
            edges[key] = NavEdge(from, to, via, confidence, evidence)
        }
    }

    // --- helpers ---

    /** R8 moves lambdas into top-level `Outer$$ExternalSyntheticLambda0` classes; they belong to `Outer`. */
    private fun ownerOf(className: String): String = className.substringBefore('$')

    /** How many camel-case words of the class name the layout name shares (`SettingsActivity` ~ `settings_activity`). */
    private fun nameOverlap(className: String, layout: String): Int {
        val words = CodeScanner.snakeCase(className.substringAfterLast('.')).split('_').toSet()
        return layout.split('_').count { it in words }
    }

    private fun graphStart(graphName: String): String? {
        val graph = facts.navGraphs.firstOrNull { it.name == graphName } ?: return null
        return graph.startDestination?.let { destinationId(graph, it) }
    }

    private fun destinationId(graph: NavGraph, id: String): String {
        val destination = graph.destinations.firstOrNull { it.id == id }
        return destination?.className?.let { ManifestParser.qualify(pkg, it) } ?: "${graph.name}#$id"
    }

    /**
     * Resolves a class token as printed in decompiled code (`Foo`, `Outer.Inner`, or fully qualified)
     * to one of [candidates]. Ambiguous simple names prefer the site's package; otherwise null.
     */
    private fun resolve(token: String, candidates: Collection<String>, site: String?): String? {
        if (token in candidates) return token
        val matches = candidates.filter { it.endsWith(".$token") }
        if (matches.size <= 1) return matches.singleOrNull()
        val sitePackage = site?.substringBeforeLast('.') ?: return null
        return matches.filter { it.startsWith("$sitePackage.") }.singleOrNull()
    }

    private fun layout(name: String): WireframeNode? = layoutCache.getOrPut(name) {
        if (name !in facts.layoutNames) return@getOrPut null
        runCatching { facts.parseLayout(name) }
            .onFailure { warnings += "layout/$name could not be parsed: ${it.message}" }
            .getOrNull()
    }

    /** All nodes of a layout, following `<include>`s (each layout visited once). */
    private fun layoutNodes(name: String, visited: MutableSet<String> = mutableSetOf()): List<WireframeNode> {
        if (!visited.add(name)) return emptyList()
        val root = layout(name) ?: return emptyList()
        return generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }
            .flatten()
            .flatMap { node -> listOf(node) + (node.include?.let { layoutNodes(it, visited) } ?: emptyList()) }
            .toList()
    }

    private fun collectLayouts(): Map<String, WireframeNode> {
        val bound = screens.values.flatMap { screen -> screen.bindings.map { it.layout } }
        val referenced = facts.scans
            .filterNot { Libraries.isLibraryClass(it.className, pkg) }
            .flatMap { it.layoutRefs }
            .filterNot(Libraries::isLibraryLayout)
        val names = sortedSetOf<String>()
        for (root in (bound + referenced).distinct()) {
            names += root
            layoutNodes(root).mapNotNullTo(names) { it.include }
        }
        return names.mapNotNull { name -> layout(name)?.let { name to it } }.toMap()
    }

    private fun addSummaryWarnings() {
        if (facts.obfuscated) {
            warnings += "Code is obfuscated (R8/ProGuard): code-based bindings and edges may be incomplete."
        }
        if (facts.composeDetected) {
            warnings += "Jetpack Compose libraries are bundled: screens built with Compose have no XML layouts, so they have no wireframes."
        }
        val unresolved = edges.values.count { it.confidence == Confidence.UNRESOLVED }
        if (unresolved > 0) {
            warnings += "$unresolved navigation edges have an unknown source screen (from = null)."
        }
        val unbound = screens.values.count { it.kind != ScreenKind.COMPOSE_ROUTE && it.bindings.isEmpty() }
        if (unbound > 0) {
            warnings += "$unbound activity/fragment screens have no layout found."
        }
    }
}
