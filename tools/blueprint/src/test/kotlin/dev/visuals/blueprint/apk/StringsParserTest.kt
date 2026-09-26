package dev.visuals.blueprint.apk

import kotlin.test.Test
import kotlin.test.assertEquals

class StringsParserTest {

    @Test
    fun `parses default strings and unescapes values`() {
        val strings = StringsParser.parse(
            """
            <resources>
                <string name="login">Login</string>
                <string name="instance">What\'s an instance?</string>
                <string name="quoted">"  spaced  "</string>
                <string name="lines">a\nb</string>
            </resources>
            """.trimIndent(),
        )
        assertEquals("Login", strings["login"])
        assertEquals("What's an instance?", strings["instance"])
        assertEquals("  spaced  ", strings["quoted"])
        assertEquals("a\nb", strings["lines"])
    }
}
