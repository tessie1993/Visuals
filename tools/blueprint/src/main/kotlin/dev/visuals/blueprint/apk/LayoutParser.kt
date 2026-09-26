package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.Role
import dev.visuals.blueprint.model.WireframeNode
import org.w3c.dom.Element

internal class LayoutParser(private val strings: Map<String, String>) {

    fun parse(xml: String): WireframeNode = node(parseXml(xml))

    private fun node(element: Element): WireframeNode {
        val type = when (element.tagName) {
            "view" -> element.plainAttr("class") ?: "view"
            else -> element.tagName
        }
        return WireframeNode(
            type = type,
            role = roleOf(type),
            id = resourceName(element.androidAttr("id"), "id"),
            text = resolveString(element.androidAttr("text"), strings),
            hint = resolveString(element.androidAttr("hint"), strings),
            contentDescription = resolveString(element.androidAttr("contentDescription"), strings),
            src = element.androidAttr("src") ?: element.appAttr("srcCompat"),
            width = element.androidAttr("layout_width"),
            height = element.androidAttr("layout_height"),
            orientation = element.androidAttr("orientation"),
            visibility = element.androidAttr("visibility"),
            include = resourceName(element.plainAttr("layout"), "layout").takeIf { type == "include" },
            fragment = (element.androidAttr("name") ?: element.plainAttr("class"))
                .takeIf { type == "fragment" || type.endsWith("FragmentContainerView") },
            navGraph = resourceName(element.appAttr("navGraph"), "navigation"),
            children = element.childElements.map(::node),
        )
    }

    companion object {
        private val EXACT = mapOf(
            "include" to Role.INCLUDE,
            "merge" to Role.CONTAINER,
            "fragment" to Role.FRAGMENT_HOST,
            "FragmentContainerView" to Role.FRAGMENT_HOST,
            "ComposeView" to Role.COMPOSE_HOST,
            "View" to Role.VIEW,
            "Space" to Role.SPACE,
            "ViewStub" to Role.VIEW,
            "WebView" to Role.WEB,
            "MaterialDivider" to Role.DIVIDER,
            "ImageButton" to Role.BUTTON,
            "AppCompatImageButton" to Role.BUTTON,
            "FloatingActionButton" to Role.FAB,
            "ExtendedFloatingActionButton" to Role.FAB,
            "TextInputLayout" to Role.INPUT,
            "AutoCompleteTextView" to Role.INPUT,
            "MaterialAutoCompleteTextView" to Role.INPUT,
            "Spinner" to Role.INPUT,
            "AppCompatSpinner" to Role.INPUT,
            "ViewPager" to Role.PAGER,
            "ViewPager2" to Role.PAGER,
            "Switch" to Role.TOGGLE,
            "SwitchCompat" to Role.TOGGLE,
            "SwitchMaterial" to Role.TOGGLE,
            "MaterialSwitch" to Role.TOGGLE,
            "CheckBox" to Role.TOGGLE,
            "MaterialCheckBox" to Role.TOGGLE,
            "RadioButton" to Role.TOGGLE,
            "MaterialRadioButton" to Role.TOGGLE,
            "ToggleButton" to Role.TOGGLE,
            "Chip" to Role.CHIP,
            "ProgressBar" to Role.PROGRESS,
            "CircularProgressIndicator" to Role.PROGRESS,
            "LinearProgressIndicator" to Role.PROGRESS,
            "SeekBar" to Role.SLIDER,
            "AppCompatSeekBar" to Role.SLIDER,
            "Slider" to Role.SLIDER,
            "RangeSlider" to Role.SLIDER,
            "RatingBar" to Role.SLIDER,
            "BottomNavigationView" to Role.NAVIGATION,
            "NavigationView" to Role.NAVIGATION,
            "NavigationRailView" to Role.NAVIGATION,
            "TabLayout" to Role.NAVIGATION,
            "BottomAppBar" to Role.NAVIGATION,
            "CardView" to Role.CARD,
            "MaterialCardView" to Role.CARD,
            "ChipGroup" to Role.CONTAINER,
            "RadioGroup" to Role.CONTAINER,
            "SwipeRefreshLayout" to Role.CONTAINER,
            "ScrollView" to Role.SCROLL,
            "NestedScrollView" to Role.SCROLL,
            "HorizontalScrollView" to Role.SCROLL,
        )

        private val SUFFIXES = listOf(
            "EditText" to Role.INPUT,
            "TextView" to Role.TEXT,
            "Button" to Role.BUTTON,
            "ImageView" to Role.IMAGE,
            "RecyclerView" to Role.LIST,
            "ListView" to Role.LIST,
            "GridView" to Role.LIST,
            "Toolbar" to Role.TOOLBAR,
            "Layout" to Role.CONTAINER,
            "ScrollView" to Role.SCROLL,
        )

        /** Classifies a view by its simple class name; unknown views are [Role.CUSTOM]. */
        fun roleOf(type: String): Role {
            val simple = type.substringAfterLast('.')
            return EXACT[simple]
                ?: SUFFIXES.firstOrNull { (suffix, _) -> simple.endsWith(suffix) }?.second
                ?: Role.CUSTOM
        }
    }
}
