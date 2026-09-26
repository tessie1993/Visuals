package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.BindingKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeScannerTest {

    @Test
    fun `finds layout bindings`() {
        val scan = CodeScanner.scan(
            "com.example.MainActivity",
            """
            setContentView(R.layout.activity_main);
            View v = ((LoginActivity) obj).getLayoutInflater().inflate(R.layout.activity_login, (ViewGroup) null, false);
            super(R.layout.fragment_home);
            ActivityDetailBinding.inflate(getLayoutInflater());
            inflater.inflate(R.layout.item_row, parent, false);
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                CodeBinding(null, "activity_main", BindingKind.SET_CONTENT_VIEW, 1),
                CodeBinding("LoginActivity", "activity_login", BindingKind.VIEW_BINDING, 2),
                CodeBinding(null, "fragment_home", BindingKind.CONSTRUCTOR, 3),
                CodeBinding(null, "activity_detail", BindingKind.VIEW_BINDING, 4),
                CodeBinding(null, "item_row", BindingKind.INFLATE, 5),
            ),
            scan.bindings,
        )
        assertEquals(setOf("activity_main", "activity_login", "fragment_home", "item_row"), scan.layoutRefs)
    }

    @Test
    fun `finds intent targets with their context variable`() {
        val scan = CodeScanner.scan(
            "a1.y0",
            """Intent intent = new Intent(mainActivity, (Class<?>) LoginActivity.class);""",
        )
        assertEquals(CodeRef("LoginActivity", 1, "mainActivity"), scan.intentTargets.single())
    }

    @Test
    fun `finds compose routes`() {
        val scan = CodeScanner.scan(
            "com.example.NavKt",
            """
            NavGraphBuilderKt.composable${'$'}default(builder, "detail/{id}", null, null, 126, null);
            NavGraphBuilderKt.composable(builder, Reflection.getOrCreateKotlinClass(Profile.class), map, list);
            NavController.navigate${'$'}default(navController, "detail/" + id, null, null, 6, null);
            navBackStack.add((NavKey) Route.Settings.INSTANCE);
            """.trimIndent(),
        )
        assertEquals(listOf("detail", "Profile"), scan.routeDeclarations.map { it.token })
        assertEquals(listOf("detail", "Route.Settings"), scan.routeNavigations.map { it.token })
    }

    @Test
    fun `converts binding class names to layout names`() {
        assertEquals("activity_main", CodeScanner.snakeCase("ActivityMain"))
        assertEquals("fragment2_pane", CodeScanner.snakeCase("Fragment2Pane"))
    }
}
