# Launch Glass

Launch Glass turns a build-time YAML service list into ordinary Android launcher
entries backed by one secure, shared WebView activity.

Each configured service is emitted as a manifest-declared `activity-alias` with
its own label and icon. To Android launchers these are regular application
components, not browser bookmarks, widgets, pinned shortcuts, or
`ShortcutManager` entries. This avoids the browser or shortcut-owner badge that
many launchers add to web shortcuts.

## Why Launch Glass?

- One lightweight native APK for multiple web applications.
- A distinct launcher entry, label, and icon for every service.
- No Flutter, Compose, React Native, bundled browser engine, or JavaScript bridge.
- Android System WebView receives normal platform security and browser updates.
- Exact-origin navigation policy with external links opened in the default browser.
- Build-time configuration keeps launcher components and CA trust declarative.
- Optional user-installed CA trust scoped independently to each configured host.
- Android 17/API 37 local-network permission requested only for services that need it.

Launch Glass deliberately does not provide runtime service editing. Android
launcher components must exist in the installed manifest. Runtime-generated
entries would require shortcut APIs and lose the regular-component behavior this
project is designed to provide.

## Example Configuration

`services.yaml` is the source of truth:

```yaml
services:
  - id: example
    component: Example
    label: Example Service
    url: https://example.invalid
    icon: example
    userCaTrust: false
    localNetwork: false
```

The committed example is intentionally minimal and uses the reserved
`example.invalid` domain. It is present so a clean checkout and public CI can
exercise generation and build an APK without embedding a real service. Replace
it with your own entries before using the app.

Every service requires:

| Key | Purpose |
| --- | --- |
| `id` | Lowercase identifier used for generated Android resources. |
| `component` | Unique Java-style suffix for the generated launcher alias. |
| `label` | Human-facing launcher label. |
| `url` | HTTPS start URL; its exact origin is the WebView allowlist. |
| `icon` | Android mipmap resource name without `@mipmap/`. |
| `userCaTrust` | Trust Android user-installed CAs only for this hostname. |
| `localNetwork` | Request API 37 local-network access before loading this service. |

The dependency-free Gradle generator accepts this intentionally small YAML
schema, validates it, and writes the following under
`app/build/generated/services`:

- Application manifest and launcher aliases
- Service labels
- Java service catalog
- Network Security Configuration

Generated files are build artifacts and are never committed. Android `preBuild`
depends on `generateServices`, so normal builds regenerate them automatically.

## Icons

Each service icon needs conventional Android resources:

```text
app/src/main/res/
  mipmap-mdpi/<icon>.png
  mipmap-hdpi/<icon>.png
  mipmap-xhdpi/<icon>.png
  mipmap-xxhdpi/<icon>.png
  mipmap-xxxhdpi/<icon>.png
  mipmap-anydpi-v26/<icon>.xml
  mipmap-anydpi-v33/<icon>.xml
```

Adaptive icons should use separate foreground and background layers and keep
important artwork inside Android's safe zone. A `monochrome` layer in the API 33
resource enables themed icons. The included `launch_glass` and `example` artwork
is original project artwork and can be replaced.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37.0
- Android Build Tools 37.0.0
- No system Gradle installation; the wrapper is checked in

Run:

```sh
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs unit tests, Android lint, and debug assembly for pull
requests, pushes to `master`, and manual dispatches. The APK artifact is named
`launch-glass-debug-apk`. Debug builds use ephemeral debug signing and may
require uninstalling an older build signed by another key.

## External Build Configuration

A private build can keep service definitions outside this repository:

```sh
./gradlew assembleDebug \
  -PservicesFile=/absolute/path/to/services.yaml \
  -PlaunchGlassApplicationId=com.example.mylauncher \
  -PlaunchGlassAppIcon=my_app_icon \
  -PlaunchGlassAppName="My Launcher"
```

The private icon resources must be present in `app/src/main/res` before Gradle
runs. A private CI repository can check out Launch Glass, overlay its private
resources, and pass these properties without publishing service definitions or
artwork in the public source repository.

Changing `applicationId` changes Android application identity. Keep it and the
release signing key stable if installed builds must upgrade in place.

## WebView Behavior

The shared activity enables JavaScript, DOM storage, first-party cookies,
persistent login state, WebSockets, system file selection, loading progress,
state restoration, and WebView history. Switching launcher aliases destroys the
previous WebView before loading the newly selected service, while persistent
cookies remain managed by System WebView.

Only the selected service's exact HTTPS origin remains in the WebView. Other
HTTP(S) origins open in the normal browser. Cross-origin POST navigation and
unexpected top-level schemes such as `file`, `content`, `javascript`, and
`intent` are blocked. Mixed content, broad file/content access, popup windows,
third-party cookies, and native JavaScript interfaces are disabled. Safe
Browsing remains enabled, and WebView debugging is enabled only for debuggable
builds.

## Private CAs and TLS

System certificate authorities remain trusted globally. When a service sets
`userCaTrust: true`, Android user-installed CAs are additionally trusted only
for that service's exact hostname. No CA certificate or private key is bundled.

TLS errors are cancelled, never bypassed. Installing a private CA does not make
an expired certificate, hostname mismatch, or incomplete chain valid.

## Local-Network Permission

For builds targeting API 37, services marked `localNetwork: true` trigger the
`ACCESS_LOCAL_NETWORK` runtime permission before their first load on Android 17
or newer. Services marked `false` do not request it. Older Android versions do
not receive the runtime request.

## Adding a Service

1. Add legacy, adaptive, and optionally monochrome icon resources.
2. Append the service to `services.yaml`.
3. Run `./gradlew generateServices` or any normal Android build.
4. Run `./gradlew test lint assembleDebug`.
5. Install on a physical device and verify label, icon masking, TLS, external
   links, login persistence, and back navigation.

## Release Signing

Do not commit release keystores. Provide a persistent keystore and credentials
through CI secrets, decode the keystore only into the runner's temporary
directory, and keep the same signing identity for every release. Losing the
keystore prevents future APKs from upgrading existing installations.

## License

Launch Glass is available under the [MIT License](LICENSE).
