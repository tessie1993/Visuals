package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.NavAction
import dev.visuals.blueprint.model.ScreenKind
import kotlin.test.Test
import kotlin.test.assertEquals

class NavGraphParserTest {

    @Test
    fun `reads destinations, actions, global actions and nested graphs`() {
        val graph = NavGraphParser.parse(
            "main",
            """
            <navigation xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:id="@+id/main" app:startDestination="@id/home">
                <fragment android:id="@+id/home" android:name="com.example.HomeFragment" android:label="@string/home">
                    <action android:id="@+id/toDetail" app:destination="@id/detail"/>
                </fragment>
                <dialog android:id="@+id/detail" android:name="com.example.DetailDialog"/>
                <action android:id="@+id/toHome" app:destination="@id/home"/>
                <navigation android:id="@+id/settings" app:startDestination="@id/prefs">
                    <fragment android:id="@+id/prefs" android:name="com.example.PrefsFragment"/>
                </navigation>
            </navigation>
            """.trimIndent(),
            mapOf("home" to "Home"),
        )
        assertEquals("home", graph.startDestination)
        assertEquals(
            listOf("home" to ScreenKind.FRAGMENT, "detail" to ScreenKind.DIALOG, "settings" to null, "prefs" to ScreenKind.FRAGMENT),
            graph.destinations.map { it.id to it.kind },
        )
        assertEquals("Home", graph.destinations.first().label)
        assertEquals(listOf(NavAction("toDetail", "home", "detail"), NavAction("toHome", null, "home")), graph.actions)
    }
}
