package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.BindingKind

/**
 * A token found at a 1-based line of a class's decompiled code. [context] is the first argument
 * of `new Intent(context, …)`; jadx names such variables after their type (e.g. `mainActivity`).
 */
internal data class CodeRef(val token: String, val line: Int, val context: String? = null)

/** A layout tied to a class. [owner] is set when the code names the class explicitly (e.g. a cast). */
internal data class CodeBinding(val owner: String?, val layout: String, val kind: BindingKind, val line: Int)

internal data class ClassScan(
    val className: String,
    val bindings: List<CodeBinding>,
    val layoutRefs: Set<String>,
    /** Class literals and string literals on Intent/ComponentName lines. */
    val intentTargets: List<CodeRef>,
    /** `new X(` and `X.newInstance(` tokens. */
    val instantiations: List<CodeRef>,
    /** Compose routes declared with `composable("route")` or `composable<Route>`. */
    val routeDeclarations: List<CodeRef>,
    /** Route strings / route class tokens on navigate or back-stack lines. */
    val routeNavigations: List<CodeRef>,
)

/**
 * Line-based scan of jadx-decompiled Java. Only patterns that jadx prints one statement per line
 * for are matched; anything else is reported as missing, never guessed.
 */
internal object CodeScanner {

    private val SET_CONTENT_VIEW = Regex("""setContentView\(R\.layout\.(\w+)""")
    private val CAST_INFLATE = Regex("""\(\((\w[\w.$]*)\)\s*[\w.$]+\)\.getLayoutInflater\(\)\.inflate\(R\.layout\.(\w+)""")
    private val SUPER_LAYOUT = Regex("""\bsuper\(R\.layout\.(\w+)""")
    private val INFLATE = Regex("""inflate\(R\.layout\.(\w+)""")
    private val BINDING_INFLATE = Regex("""\b(\w+)Binding\.inflate\(""")
    private val LAYOUT_REF = Regex("""R\.layout\.(\w+)""")
    private val CLASS_LITERAL = Regex("""\b(\w[\w.$]*)\.class\b""")
    private val STRING_LITERAL = Regex(""""(\w[\w.$]*)"""")
    private val INTENT_CONTEXT = Regex("""\bnew (?:Intent|ComponentName)\((?:this\.)?(\w+)\s*,""")
    private val NEW_INSTANCE = Regex("""\bnew\s+(\w[\w.$]*)\(""")
    private val FACTORY = Regex("""\b(\w[\w.$]*?)(?:\.Companion)?\.newInstance\(""")
    private val INSTANCE_REF = Regex("""\b(\w[\w.$]*)\.INSTANCE\b""")
    private val COMPOSABLE_ROUTE = Regex("""\bcomposable(?:\${'$'}default)?\([^"]*"([^"]+)"""")
    private val TYPED_ROUTE = Regex("""getOrCreateKotlinClass\((\w[\w.$]*)\.class\)""")
    private val NAVIGATE_ROUTE = Regex("""\bnavigate(?:\${'$'}default)?\([^"]*"([^"]+)"""")

    fun scan(className: String, code: String): ClassScan {
        val bindings = mutableListOf<CodeBinding>()
        val layoutRefs = mutableSetOf<String>()
        val intentTargets = mutableListOf<CodeRef>()
        val instantiations = mutableListOf<CodeRef>()
        val routeDeclarations = mutableListOf<CodeRef>()
        val routeNavigations = mutableListOf<CodeRef>()

        code.lineSequence().forEachIndexed { index, line ->
            val lineNo = index + 1
            LAYOUT_REF.findAll(line).mapTo(layoutRefs) { it.groupValues[1] }

            val cast = CAST_INFLATE.find(line)
            when {
                cast != null ->
                    bindings += CodeBinding(cast.groupValues[1], cast.groupValues[2], BindingKind.VIEW_BINDING, lineNo)
                SET_CONTENT_VIEW.containsMatchIn(line) ->
                    bindings += CodeBinding(null, SET_CONTENT_VIEW.find(line)!!.groupValues[1], BindingKind.SET_CONTENT_VIEW, lineNo)
                SUPER_LAYOUT.containsMatchIn(line) ->
                    bindings += CodeBinding(null, SUPER_LAYOUT.find(line)!!.groupValues[1], BindingKind.CONSTRUCTOR, lineNo)
                INFLATE.containsMatchIn(line) ->
                    bindings += CodeBinding(null, INFLATE.find(line)!!.groupValues[1], BindingKind.INFLATE, lineNo)
            }
            BINDING_INFLATE.findAll(line).forEach {
                bindings += CodeBinding(null, snakeCase(it.groupValues[1]), BindingKind.VIEW_BINDING, lineNo)
            }

            if ("Intent" in line || "ComponentName" in line || "setClass" in line) {
                val context = INTENT_CONTEXT.find(line)?.groupValues?.get(1)
                CLASS_LITERAL.findAll(line).mapTo(intentTargets) { CodeRef(it.groupValues[1], lineNo, context) }
                STRING_LITERAL.findAll(line).mapTo(intentTargets) { CodeRef(it.groupValues[1], lineNo, context) }
            }
            NEW_INSTANCE.findAll(line).mapTo(instantiations) { CodeRef(it.groupValues[1], lineNo) }
            FACTORY.findAll(line).mapTo(instantiations) { CodeRef(it.groupValues[1], lineNo) }

            if ("composable" in line) {
                COMPOSABLE_ROUTE.findAll(line).mapTo(routeDeclarations) { CodeRef(routeBase(it.groupValues[1]), lineNo) }
                TYPED_ROUTE.findAll(line).mapTo(routeDeclarations) { CodeRef(it.groupValues[1], lineNo) }
            }
            if ("navigate" in line || ".add(" in line) {
                NAVIGATE_ROUTE.findAll(line).mapTo(routeNavigations) { CodeRef(routeBase(it.groupValues[1]), lineNo) }
                NEW_INSTANCE.findAll(line).mapTo(routeNavigations) { CodeRef(it.groupValues[1], lineNo) }
                INSTANCE_REF.findAll(line).mapTo(routeNavigations) { CodeRef(it.groupValues[1], lineNo) }
            }
        }
        return ClassScan(className, bindings, layoutRefs, intentTargets, instantiations, routeDeclarations, routeNavigations)
    }

    /** `ActivityMain` → `activity_main` (the name ViewBinding derives its class from). */
    fun snakeCase(camel: String): String =
        camel.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()

    /** `detail/{id}?tab={tab}` → `detail`: the stable prefix used to match navigate() calls. */
    fun routeBase(route: String): String = route.substringBefore('/').substringBefore('?').substringBefore('{')
}
