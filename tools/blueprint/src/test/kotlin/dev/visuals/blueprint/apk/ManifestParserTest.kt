package dev.visuals.blueprint.apk

import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestParserTest {

    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example" android:versionCode="7" android:versionName="1.2">
            <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36"/>
            <application android:label="@string/app_name">
                <activity android:name=".MainActivity"/>
                <activity android:name="com.example.detail.DetailActivity" android:label="Detail"/>
                <activity android:name="SettingsActivity"/>
                <activity-alias android:name=".Launcher" android:targetActivity=".MainActivity">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity-alias>
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `reads app info and qualifies activity names`() {
        val parsed = ManifestParser.parse(manifest, mapOf("app_name" to "Example"))
        assertEquals("com.example", parsed.app.packageName)
        assertEquals("1.2", parsed.app.versionName)
        assertEquals("24", parsed.app.minSdk)
        assertEquals("Example", parsed.app.label)
        assertEquals(
            listOf("com.example.MainActivity", "com.example.detail.DetailActivity", "com.example.SettingsActivity"),
            parsed.activities.map { it.name },
        )
    }

    @Test
    fun `launcher comes from an activity-alias target`() {
        val parsed = ManifestParser.parse(manifest, emptyMap())
        assertEquals(listOf("com.example.MainActivity"), parsed.activities.filter { it.launcher }.map { it.name })
    }
}
