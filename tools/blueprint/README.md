# blueprint

Command-line tool that turns an Android APK into a machine-readable blueprint of its UI:
screens, navigation between them, and layout wireframes. It decompiles the APK with
[jadx](https://github.com/skylot/jadx) (used as a library); nothing is executed.

## Usage

```sh
./gradlew installDist
build/install/blueprint/bin/blueprint apk path/to/app.apk [-o out-dir]
```

Writes to `out-dir` (default `./<apk name>-blueprint`):

| File | Contents |
|---|---|
| `blueprint.json` | The blueprint (schema below). |
| `navigation.mmd` | [Mermaid](https://mermaid.js.org) flowchart of the navigation. Solid arrows are declared or direct, dashed arrows are inferred or unresolved, `?` is an unknown source. |

Only analyse APKs you own or have the rights to.

## `blueprint.json` (schema version 1)

| Field | Description |
|---|---|
| `source` | APK file name and SHA-256. |
| `app` | Package, version, min/target SDK, label (from the manifest). |
| `ui.views` | App XML layouts were found. |
| `ui.compose` | Jetpack Compose libraries are bundled (not which screens use them). |
| `screens[]` | `id` (class name or route), `kind` (`activity`, `fragment`, `dialog`, `compose-route`), `launcher`, `label`, `layouts[]`. |
| `screens[].layouts[]` | `layout`, `how` it was bound, `evidence` (`class:line` or resource). The first entry is the main layout. |
| `navigation[]` | `from` (screen id, or absent when unknown), `to`, `via`, `confidence`, `evidence`. |
| `navGraphs[]` | Navigation component graphs as declared in `res/navigation`. |
| `layouts{}` | Layout name → wireframe tree: `type`, `role`, `id`, `text`/`hint`/`contentDescription` (resolved from `@string`), `src`, `width`, `height`, `orientation`, `visibility`, `include`, `fragment`, `navGraph`, `children`. |
| `warnings[]` | What the extraction could not cover. |

### How things are found

- **Screens**: manifest activities; navigation-graph destinations; `*Fragment` classes that bind a
  layout or are instantiated by app code; fragments hosted in a screen's layout; Compose routes
  (Navigation 3 `NavKey` types, `composable("route")`, `composable<Route>`).
- **Layouts**: `setContentView(R.layout.x)`, view binding (including R8's
  `((Screen) x).getLayoutInflater().inflate(...)`), `super(R.layout.x)`, `inflate(R.layout.x)`.
  When nothing better is found, `activity_<name>` / `fragment_<name>` is matched by name and
  labelled `name-convention`.
- **Navigation**: navigation-graph actions, NavHost start destinations and hosted fragments from
  layouts, Intents to known activities, fragment instantiation, Compose route navigation.
- **Wireframe roles**: `container`, `scroll`, `text`, `button`, `fab`, `image`, `input`, `list`,
  `pager`, `toggle`, `chip`, `progress`, `slider`, `toolbar`, `navigation`, `card`, `web`,
  `divider`, `space`, `fragment-host`, `compose-host`, `include`, `view`, `custom`.

### Confidence

| Value | Meaning |
|---|---|
| `declared` | Stated in a resource (navigation graph, layout). |
| `direct` | Found in the source screen's own code (including its R8 lambda classes). |
| `inferred` | Found in a helper class; the source comes from the Intent's context variable (jadx names it after its type, e.g. `mainActivity`) or from the only screen that uses the helper. R8 merges lambdas across classes, so treat it as a hint. |
| `unresolved` | Found in code, source screen unknown (`from` is absent). |

## Limits

- Screens built with Compose have no XML layouts, so they get no wireframe.
- Compose navigation usually runs in lambdas jadx moves out of the route's content, so the source
  route of a Compose edge is normally unknown; the hosting activity is noted in `evidence`.
- Code-based results depend on what survives R8 obfuscation; manifest activities and resource
  names are always recovered (jadx restores shortened resource paths).
- Known library classes and layouts (`androidx.*`, `abc_*`, `mtrl_*`, …) are excluded; see
  `apk/Libraries.kt`.

## Checked against

| APK (F-Droid) | Screens | Edges | Layouts |
|---|---|---|---|
| Tusky 32.2 (Views, R8) | 35 | 62 | 105 |
| Catima 2.45.0 | 16 | 17 | 39 |
| Read You 0.16.2 (Compose, Navigation 3) | 25 (22 routes) | 23 | 0 |

Tusky (7,080 classes) takes about 25 s on 4 cores.

## Development

```sh
./gradlew test
```
