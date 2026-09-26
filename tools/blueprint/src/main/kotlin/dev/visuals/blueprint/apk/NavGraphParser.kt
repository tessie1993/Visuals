package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.NavAction
import dev.visuals.blueprint.model.NavDestination
import dev.visuals.blueprint.model.NavGraph
import dev.visuals.blueprint.model.ScreenKind
import org.w3c.dom.Element

/** Parses a decoded `res/navigation/<name>.xml`. Nested graphs are flattened into one graph. */
internal object NavGraphParser {

    private val DESTINATION_KINDS = mapOf(
        "fragment" to ScreenKind.FRAGMENT,
        "activity" to ScreenKind.ACTIVITY,
        "dialog" to ScreenKind.DIALOG,
        "navigation" to null,
    )

    fun parse(name: String, xml: String, strings: Map<String, String>): NavGraph {
        val root = parseXml(xml)
        val destinations = mutableListOf<NavDestination>()
        val actions = mutableListOf<NavAction>()
        collect(root, owner = null, destinations, actions, strings)
        return NavGraph(
            name = name,
            startDestination = resourceName(root.appAttr("startDestination"), "id"),
            destinations = destinations,
            actions = actions,
        )
    }

    private fun collect(
        graph: Element,
        owner: String?,
        destinations: MutableList<NavDestination>,
        actions: MutableList<NavAction>,
        strings: Map<String, String>,
    ) {
        for (child in graph.childElements) {
            when (child.tagName) {
                "action" -> child.toAction(from = owner)?.let(actions::add)
                in DESTINATION_KINDS -> {
                    val id = resourceName(child.androidAttr("id"), "id") ?: continue
                    destinations += NavDestination(
                        id = id,
                        kind = DESTINATION_KINDS[child.tagName],
                        className = child.androidAttr("name"),
                        label = resolveString(child.androidAttr("label"), strings),
                    )
                    if (child.tagName == "navigation") {
                        collect(child, owner = null, destinations, actions, strings)
                    } else {
                        child.childElements.filter { it.tagName == "action" }
                            .mapNotNullTo(actions) { it.toAction(from = id) }
                    }
                }
            }
        }
    }

    private fun Element.toAction(from: String?): NavAction? {
        val id = resourceName(androidAttr("id"), "id") ?: return null
        val to = resourceName(appAttr("destination"), "id") ?: return null
        return NavAction(id = id, from = from, to = to)
    }
}
