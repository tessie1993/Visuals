package dev.visuals.blueprint

import dev.visuals.blueprint.model.Blueprint
import dev.visuals.blueprint.model.Confidence

/** Renders a blueprint's navigation as a Mermaid flowchart. */
object MermaidWriter {

    fun write(blueprint: Blueprint): String = buildString {
        appendLine("flowchart LR")
        val nodeIds = LinkedHashMap<String, String>()
        fun node(id: String): String = nodeIds.getOrPut(id) { "n${nodeIds.size}" }

        blueprint.screens.forEach { screen ->
            appendLine("""  ${node(screen.id)}["${escape(screen.id.substringAfterLast('.'))}<br/><small>${screen.kind.name.lowercase()}</small>"]""")
        }
        blueprint.navigation.flatMap { listOf(it.to) + listOfNotNull(it.from) }
            .filterNot { it in nodeIds }
            .forEach { appendLine("""  ${node(it)}["${escape(it.substringAfterLast('.'))}"]""") }

        blueprint.screens.filter { it.launcher }.forEach { appendLine("  start((start)) --> ${node(it.id)}") }
        if (blueprint.navigation.any { it.from == null }) appendLine("""  unknown(("?"))""")

        blueprint.navigation.forEach { edge ->
            val from = edge.from?.let(::node) ?: "unknown"
            val arrow = if (edge.confidence == Confidence.DECLARED || edge.confidence == Confidence.DIRECT) "-->" else "-.->"
            appendLine("  $from $arrow|${edge.via.name.lowercase()}| ${node(edge.to)}")
        }
    }

    private fun escape(text: String): String = text.replace("\"", "#quot;")
}
