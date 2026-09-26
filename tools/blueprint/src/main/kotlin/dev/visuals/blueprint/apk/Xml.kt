package dev.visuals.blueprint.apk

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
internal const val APP_NS = "http://schemas.android.com/apk/res-auto"

/** Parses decoded resource XML. APKs are untrusted input, so DTDs and entities are rejected. */
internal fun parseXml(text: String): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val builder = factory.newDocumentBuilder().apply { setErrorHandler(null) }
    return builder.parse(InputSource(StringReader(text))).documentElement
}

internal fun Element.androidAttr(name: String): String? = getAttributeNS(ANDROID_NS, name).ifEmpty { null }

internal fun Element.appAttr(name: String): String? = getAttributeNS(APP_NS, name).ifEmpty { null }

internal fun Element.plainAttr(name: String): String? = getAttribute(name).ifEmpty { null }

internal val Element.childElements: List<Element>
    get() = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

private val RESOURCE_REF = Regex("""^@\+?(?:[\w.]+:)?(\w+)/([\w.]+)$""")

/** Returns the resource name of `@type/name` (also `@+id/name`, `@pkg:type/name`), or null. */
internal fun resourceName(value: String?, type: String): String? {
    val match = value?.let { RESOURCE_REF.matchEntire(it) } ?: return null
    return match.groupValues[2].takeIf { match.groupValues[1] == type }
}
