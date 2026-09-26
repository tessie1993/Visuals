package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutParserTest {

    private val parser = LayoutParser(mapOf("sign_in" to "Sign in"))

    @Test
    fun `builds a wireframe tree with roles and resolved text`() {
        val root = parser.parse(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent">
                <com.google.android.material.appbar.MaterialToolbar android:id="@+id/toolbar"/>
                <com.google.android.material.button.MaterialButton android:id="@+id/signIn" android:text="@string/sign_in"/>
                <include layout="@layout/footer"/>
                <androidx.fragment.app.FragmentContainerView android:name="androidx.navigation.fragment.NavHostFragment"
                    app:navGraph="@navigation/main_graph"/>
                <com.example.ui.FancyView/>
            </LinearLayout>
            """.trimIndent(),
        )
        assertEquals(Role.CONTAINER, root.role)
        assertEquals("vertical", root.orientation)
        val (toolbar, button, include, host, custom) = root.children
        assertEquals(Role.TOOLBAR to "toolbar", toolbar.role to toolbar.id)
        assertEquals(Role.BUTTON to "Sign in", button.role to button.text)
        assertEquals(Role.INCLUDE to "footer", include.role to include.include)
        assertEquals(Role.FRAGMENT_HOST, host.role)
        assertEquals("androidx.navigation.fragment.NavHostFragment" to "main_graph", host.fragment to host.navGraph)
        assertEquals(Role.CUSTOM, custom.role)
    }

    @Test
    fun `classifies by simple name and suffix`() {
        assertEquals(Role.INPUT, LayoutParser.roleOf("com.google.android.material.textfield.TextInputEditText"))
        assertEquals(Role.LIST, LayoutParser.roleOf("androidx.recyclerview.widget.RecyclerView"))
        assertEquals(Role.FAB, LayoutParser.roleOf("com.google.android.material.floatingactionbutton.FloatingActionButton"))
        assertEquals(Role.CONTAINER, LayoutParser.roleOf("androidx.constraintlayout.widget.ConstraintLayout"))
        assertEquals(Role.TEXT, LayoutParser.roleOf("com.example.EmojiTextView"))
    }
}
