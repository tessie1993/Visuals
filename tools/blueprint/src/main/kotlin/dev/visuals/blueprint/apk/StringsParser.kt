package dev.visuals.blueprint.apk

internal object StringsParser {

    /** Parses a decoded `res/values/strings.xml` into name → display value. */
    fun parse(xml: String): Map<String, String> = parseXml(xml).childElements
        .filter { it.tagName == "string" }
        .associate { it.plainAttr("name").orEmpty() to unescape(it.textContent.trim()) }
        .filterKeys { it.isNotEmpty() }

    /**
     * Applies Android string-resource escaping: an enclosing pair of double quotes is dropped and
     * `\'`, `\"`, `\\`, `\@`, `\?`, `\n`, `\t` are replaced by the characters they stand for.
     */
    fun unescape(raw: String): String {
        val text = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
        return buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    when (val next = text[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}
