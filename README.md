# Visuals

Tooling for an Android (Kotlin / Jetpack Compose) UI pipeline: extract a blueprint from an existing
app, rebuild its UI in Compose, and render the result to images you can look at.

| Path | What it is |
|---|---|
| [`tools/blueprint`](tools/blueprint) | CLI. `blueprint apk <file.apk>` extracts screens, navigation and layout wireframes from an APK; `blueprint motion <video>` measures UI motion and writes a motion spec, Compose code and a Lottie file. |
| [`renderer`](renderer) | Android library whose unit test renders Compose components to PNG (Robolectric + Roborazzi). |
| [`forks/music-visualizer`](forks/music-visualizer) | The earlier web music visualizer (React + Vite), kept as-is. |

## Requirements

- JDK 21
- Android SDK with `platforms/android-37.2` and `build-tools/37.0.0` (renderer only), `ANDROID_HOME` set
- On Linux, the GTK 2 runtime for `blueprint motion` (`libgtk2.0-0t64` on Ubuntu 24.04)
- Gradle is not needed: each project ships a Gradle 9.8.0 wrapper

## Quick start

```sh
# Build the CLI once
(cd tools/blueprint && ./gradlew installDist)

# Blueprint of an APK -> ./app-blueprint/blueprint.json + navigation.mmd
tools/blueprint/build/install/blueprint/bin/blueprint apk path/to/app.apk -o app-blueprint

# Motion in a screen recording -> ./clip-motion/motion.json + Motion.kt + motion.lottie.json
tools/blueprint/build/install/blueprint/bin/blueprint motion path/to/clip.mp4 --px-per-dp 2.625

# Render every registered Compose component -> renderer/build/renders/*.png
(cd renderer && ./gradlew testDebugUnitTest -Proborazzi.test.record=true)
```
