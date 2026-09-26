package dev.visuals.suite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.visuals.suite.components.componentRegistry
import dev.visuals.suite.theme.SuiteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every entry of [componentRegistry] to `build/renders/<Name>-<light|dark>.png`.
 * Pass `-Pvisuals.component=<Name>` to render a single component.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class RenderComponentsTest(
    private val name: String,
    private val darkTheme: Boolean,
) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun render() {
        val content = checkNotNull(componentRegistry[name]) { "Unknown component: $name" }
        composeRule.setContent {
            SuiteTheme(darkTheme = darkTheme) {
                Surface {
                    Box(Modifier.testTag(TAG).padding(16.dp)) { content() }
                }
            }
        }
        val variant = if (darkTheme) "dark" else "light"
        composeRule.onNodeWithTag(TAG).captureRoboImage("build/renders/$name-$variant.png")
    }

    companion object {
        private const val TAG = "component"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun targets(): List<Array<Any>> {
            val only = System.getProperty("visuals.component")
            val names = componentRegistry.keys.filter { only == null || it == only }
            check(names.isNotEmpty()) { "No component named '$only' in componentRegistry" }
            return names.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
        }
    }
}
