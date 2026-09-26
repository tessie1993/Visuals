# Visuals

Tooling for an Android (Kotlin / Jetpack Compose) UI pipeline: extract a blueprint from an existing
app, rebuild its UI in Compose, and render the result to images you can look at.

| Path | What it is |
|---|---|
| [`tools/blueprint`](tools/blueprint) | CLI. `blueprint apk <file.apk>` extracts screens, navigation and layout wireframes from an APK. |
| [`renderer`](renderer) | Android library whose unit test renders Compose components to PNG (Robolectric + Roborazzi). |
| [`forks/music-visualizer`](forks/music-visualizer) | The earlier web music visualizer (React + Vite), kept as-is. |

## Requirements

- JDK 21
- Android SDK with `platforms/android-37.2` and `build-tools/37.0.0` (renderer only), `ANDROID_HOME` set
- Gradle is not needed: each project ships a Gradle 9.8.0 wrapper

## Quick start

```sh
# Blueprint of an APK -> ./app-blueprint/blueprint.json + navigation.mmd
cd tools/blueprint && ./gradlew installDist
build/install/blueprint/bin/blueprint apk path/to/app.apk -o app-blueprint

# Render every registered Compose component -> renderer/build/renders/*.png
cd renderer && ./gradlew testDebugUnitTest -Proborazzi.test.record=true
```
