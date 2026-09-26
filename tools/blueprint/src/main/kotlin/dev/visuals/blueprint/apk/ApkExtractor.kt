package dev.visuals.blueprint.apk

import dev.visuals.blueprint.model.Blueprint
import dev.visuals.blueprint.sha256
import dev.visuals.blueprint.model.Source
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.api.impl.NoOpCodeCache
import jadx.api.impl.SimpleCodeWriter
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reads an APK with jadx and produces its [Blueprint]. */
class ApkExtractor(private val log: (String) -> Unit = {}) {

    fun extract(apk: File): Blueprint {
        require(apk.isFile) { "Not a file: $apk" }
        val args = JadxArgs().apply {
            setInputFile(apk)
            setCodeCache(NoOpCodeCache())
            setCodeWriterProvider(::SimpleCodeWriter)
            setShowInconsistentCode(true)
        }
        JadxDecompiler(args).use { jadx ->
            log("Loading ${apk.name}")
            jadx.load()
            val resources = ResourceIndex(jadx.resources)
            val strings = resources.strings()
            val manifest = ManifestParser.parse(resources.manifest(), strings)
            val navGraphs = resources.navigations.map { (name, file) ->
                NavGraphParser.parse(name, resources.text(file), strings)
            }
            val layoutParser = LayoutParser(strings)
            val pkg = manifest.app.packageName

            val classes = jadx.classes
            val appClasses = classes.filterNot { Libraries.isLibraryClass(it.fullName, pkg) }
            log("Decompiling ${appClasses.size} of ${classes.size} classes")
            val scans = scan(jadx, appClasses)

            val byName = classes.associateBy { it.fullName }
            val facts = ApkFacts(
                manifest = manifest,
                navGraphs = navGraphs,
                layoutNames = resources.layouts.keys,
                parseLayout = { name -> layoutParser.parse(resources.text(resources.layouts.getValue(name))) },
                scans = scans,
                classNames = jadx.classesWithInners.mapTo(HashSet()) { it.fullName },
                routeKeyClasses = routeKeyClasses(jadx.classesWithInners),
                usersOf = { name ->
                    byName[name]?.useIn.orEmpty().mapNotNullTo(HashSet()) { it.topParentClass?.fullName }
                },
                composeDetected = resources.hasComposeMetadata || classes.any { it.fullName.startsWith("androidx.compose.") },
                obfuscated = isObfuscated(appClasses),
            )
            log("Assembling blueprint")
            return BlueprintAssembler(facts).assemble(Source(apk.name, apk.sha256()))
        }
    }

    /**
     * Decompiles in jadx's own dependency-ordered batches, one batch per task. Batches can pull in
     * dependencies outside [classes]; those are decompiled but not scanned.
     */
    private fun scan(jadx: JadxDecompiler, classes: List<JavaClass>): List<ClassScan> {
        val wanted = classes.toHashSet()
        val results = ConcurrentLinkedQueue<ClassScan>()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            jadx.decompileScheduler.buildBatches(classes).forEach { batch ->
                pool.execute {
                    batch.filter { it in wanted }.forEach { cls ->
                        runCatching { CodeScanner.scan(cls.fullName, cls.code) }
                            .onSuccess(results::add)
                            .onFailure { log("Skipped ${cls.fullName}: ${it.message}") }
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.HOURS)
        }
        return results.sortedBy { it.className }
    }

    /** Concrete classes whose supertypes include Navigation 3's `NavKey`. */
    private fun routeKeyClasses(classes: List<JavaClass>): Set<String> {
        val nodes = classes.associateBy { it.rawName }
        val supertypes = nodes.mapValues { (_, cls) ->
            val node = cls.classNode
            listOfNotNull(node.superClass?.`object`) + node.interfaces.map { it.`object` }
        }
        val memo = HashMap<String, Boolean>()
        fun isRoute(raw: String, depth: Int = 0): Boolean = memo.getOrPut(raw) {
            raw == NAV_KEY || (depth < MAX_TYPE_DEPTH && supertypes[raw].orEmpty().any { isRoute(it, depth + 1) })
        }
        return nodes.filter { (raw, cls) ->
            !cls.classNode.accessFlags.isInterface && !cls.classNode.accessFlags.isAbstract && isRoute(raw)
        }.values.mapTo(sortedSetOf()) { it.fullName }
    }

    private fun isObfuscated(classes: List<JavaClass>): Boolean =
        classes.isNotEmpty() && classes.count { it.name.length <= 2 } > classes.size * OBFUSCATED_SHARE

    private companion object {
        const val NAV_KEY = "androidx.navigation3.runtime.NavKey"
        const val MAX_TYPE_DEPTH = 16
        const val OBFUSCATED_SHARE = 0.3
    }
}

/** Decoded resources by their de-obfuscated path (R8 may shorten `res/layout/x.xml` to `res/Ab.xml`). */
private class ResourceIndex(files: List<ResourceFile>) {

    private val byPath = files.associateBy { it.deobfName ?: it.originalName }
    val layouts: Map<String, ResourceFile> = byConfig("layout")
    val navigations: Map<String, ResourceFile> = byConfig("navigation")
    val hasComposeMetadata = files.any { it.originalName.startsWith("META-INF/androidx.compose") }

    fun manifest(): String = text(checkNotNull(byPath["AndroidManifest.xml"]) { "APK has no AndroidManifest.xml" })

    fun text(file: ResourceFile): String = file.loadContent().text.codeStr

    /** Default-config `res/values/strings.xml`, decoded from `resources.arsc`. */
    fun strings(): Map<String, String> {
        val table = byPath.values.firstOrNull { it.type == ResourceType.ARSC } ?: return emptyMap()
        val values = table.loadContent().subFiles.firstOrNull { it.name == "res/values/strings.xml" } ?: return emptyMap()
        return StringsParser.parse(values.text.codeStr)
    }

    /** `res/<type>[-qualifiers]/<name>.xml` by name, preferring the unqualified directory. */
    private fun byConfig(type: String): Map<String, ResourceFile> {
        val pattern = Regex("""^res/$type(-[^/]+)?/(\w+)\.xml$""")
        return byPath.entries
            .mapNotNull { (path, file) -> pattern.matchEntire(path)?.let { Triple(it.groupValues[2], it.groupValues[1], file) } }
            .sortedWith(compareBy({ it.first }, { it.second.isNotEmpty() }, { it.second }))
            .distinctBy { it.first }
            .associate { it.first to it.third }
    }
}
