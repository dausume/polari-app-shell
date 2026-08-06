# polari-app-shell

Native app shells for Polari instances (plan: appstore-1 / shell-1).
The shell is thin: it registers instances, probes reachability,
pins the instance CA, and renders the instance's existing Angular
web UI in an embedded browser — Keycloak login happens ONCE on first
launch (PKCE S256), silently after that via the persisted
per-instance browser profile.

One Gradle repo, platform modules (the committed matrix):

| module | status | browser |
|---|---|---|
| `:core` | ✅ | — (shared plumbing, unit-tested) |
| `:desktop` | ✅ Linux x64 | JavaFX chrome + JCEF |
| `:android` phone flavor | ✅ APK builds | embedded WebView (pinned CA, API 29+ for self-signed) |
| `:android` vr flavor | ✅ APK builds (Quest 2 / Vive) | **Wolvic, required** — the WebXR browser; shell registers/probes, Wolvic renders |
| `ios/` | sources complete, **needs a Mac** (XcodeGen) | WKWebView |
| PinePhone | phase 5 | JCEF if stable, else Chromium `--app` mode |

`:android` only loads when an SDK is present (`local.properties` /
`ANDROID_HOME`): `./gradlew :android:assemblePhoneDebug
:android:assembleVrDebug`. Store APKs ship config-free — enrollment
is deep-link/QR (`polari://register?...`), the manifest owns the
scheme.

## Build & run

Any JDK ≥ 21 (the wrapper fetches Gradle itself):

    ./gradlew :core:test          # contract unit tests
    ./gradlew :desktop:run        # needs a config source, see below
    ./gradlew srcDistTar          # the archive the App Store overlays

Config precedence (first hit wins; later sources only ADD
instances): `--config <path>` → sidecar `polari-shell.json` next to
the launcher → baked classpath `/config/polari-shell.json` →
`--deeplink 'polari://register?...'` → manual. After first-run merge
the registry (`~/.config/polari-shell/instances.json`, 0600) is
authoritative.

    ./gradlew :desktop:run --args="--config /path/to/polari-shell.json"

## Shared contracts

- `config/polari-shell.schema.json` — the registration document v1;
  the backend (`polari-framework/modules/appstore`) emits exactly
  this shape. Change them in lockstep or not at all.
- `docs/BRIDGE_CONTRACT.md` — the window.PolariShell JSON bridge.
- Deep link: `polari://register?api=…&t=…&ca=…&scope=…&nk=…&nn=…`
  (ca = TOFU pin; scope/nk/nn phrase the network advisory offline).

## Publishing to a store

    ./gradlew srcDistTar
    # → build/dist/polari-app-shell-src.tar.gz
    # admin-upload it: POST /api/appstore/artifacts
    #   {action: presign-put, shellName, platform: gradle-project,
    #    version} → PUT the file → {action: commit, ...}

Downloads then overlay `config/polari-shell.json` + a README into
that archive per request — deterministically, never rebuilt.

## First-run notes

- jcefmaven downloads CEF natives (~150 MB) to
  `~/.local/share/polari-shell/jcef-natives` on first `:desktop:run`.
- Wayland: the launcher forces X11 (`GDK_BACKEND=x11`) — JCEF
  windowed mode on Wayland is not there yet.
- If JCEF cannot initialize, the shell opens the instance in the
  system browser instead of showing a dead window (honest degrade;
  CA must then be OS-trusted).
