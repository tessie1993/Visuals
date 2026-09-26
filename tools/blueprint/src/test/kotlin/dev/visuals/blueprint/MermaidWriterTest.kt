package dev.visuals.blueprint

import dev.visuals.blueprint.model.AppInfo
import dev.visuals.blueprint.model.Blueprint
import dev.visuals.blueprint.model.Confidence
import dev.visuals.blueprint.model.NavEdge
import dev.visuals.blueprint.model.NavVia
import dev.visuals.blueprint.model.Screen
import dev.visuals.blueprint.model.ScreenKind
import dev.visuals.blueprint.model.Source
import dev.visuals.blueprint.model.UiToolkit
import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidWriterTest {

    @Test
    fun `draws screens, the launcher, and solid or dashed edges by confidence`() {
        val blueprint = Blueprint(
            schemaVersion = Blueprint.SCHEMA_VERSION,
            source = Source("app.apk", "0"),
            app = AppInfo("com.example", null, null, null, null, null),
            ui = UiToolkit(views = true, compose = false),
            screens = listOf(
                Screen("com.example.MainActivity", ScreenKind.ACTIVITY, launcher = true),
                Screen("com.example.DetailActivity", ScreenKind.ACTIVITY),
            ),
            navigation = listOf(
                NavEdge("com.example.MainActivity", "com.example.DetailActivity", NavVia.INTENT, Confidence.DIRECT, "x:1"),
                NavEdge(null, "com.example.DetailActivity", NavVia.INTENT, Confidence.UNRESOLVED, "y:2"),
            ),
            navGraphs = emptyList(),
            layouts = emptyMap(),
            warnings = emptyList(),
        )
        assertEquals(
            """
            flowchart LR
              n0["MainActivity<br/><small>activity</small>"]
              n1["DetailActivity<br/><small>activity</small>"]
              start((start)) --> n0
              unknown(("?"))
              n0 -->|intent| n1
              unknown -.->|intent| n1

            """.trimIndent(),
            MermaidWriter.write(blueprint),
        )
    }
}
