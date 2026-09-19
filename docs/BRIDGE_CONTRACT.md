# Polari shell bridge contract v1 (plan S4)

One JSON bridge, three transports, identical surface:

| Platform | transport |
|---|---|
| desktop (JCEF) | `CefMessageRouter` (`window.cefQuery`) |
| Android | `@JavascriptInterface` |
| iOS | `WKScriptMessageHandler` |

All are normalized by an injected script into:

```js
window.PolariShell = {
  version: 1,
  request(msg): Promise<response>,   // envelope below
}
// shell -> page events:
window.addEventListener('polari-shell', (e) => e.detail /* event */)
```

## Envelope

Request: `{ "v": 1, "id": "<uuid>", "type": "<name>", "payload": {...} }`
Response: `{ "id": "<uuid>", "ok": true, "payload": {...} }`
       or `{ "id": "<uuid>", "ok": false, "error": {"code": "", "message": ""} }`

## Phase-1 messages

| type | payload | response payload |
|---|---|---|
| `shell.info` | `{}` | `{shellVersion, platform, instanceId, scope, appName}` — lets the SPA detect it is embedded; sep-1: `scope`/`appName` mirror the registration's app block (`instance` + `""` when unclamped). The URL (`?shellApp=`) stays the clamp channel of record — this is garnish for shell-aware pages |
| `shell.instance.list` | `{}` | `{instances: [{id, displayName, reachability: {state, at, evidence}}]}` |
| `shell.instance.switch` | `{id}` | `{switched: true}` — the shell swaps the browser |
| `shell.reachability.get` | `{}` | current instance probe state |
| `shell.open.external` | `{url}` | `{opened: true}` — OS browser |
| `shell.capabilities` | `{}` | `{capabilities: [name, ...]}` — sep-5: the registration's declared edge-behavior REFERENCES (AppEdgeBehavior rows; resolve via `GET /api/appstore/behaviors?names=...`). Declaration only — the gate (CapabilityGate + §5l helpers) refuses anything undeclared, default deny-all |

Store messages (§31, desktop): `store.available {}` →
`{available, mechanism}`; `store.install {name}` → pkexec via the
fixed HostInstall argv; `store.status {name, kind?}` → installed
version by dpkg — sep-2: `kind` picks the launcher package prefix
(`isle` default | `polari`), same allowlist discipline as the name;
unin-4 adds `deployed` (mesh-app compose project present on this
device). `store.uninstall {name, purge?}` → pkexec
`isle store uninstall <name> --yes [--purge]` (fixed argv, same
allowlist — the UI holds ZERO teardown logic; the engine verb owns
the data policy: plain uninstall preserves data, purge backs up then
erases). `store.removeIsle {}` → opens a terminal running
`pkexec isle uninstall --everything` (interactive verb — yes/no and
the core-cascade typed confirmation live in the terminal, exactly
the store-launch.sh unin-7 pattern); replies `{ok, terminal|error}`.

Pipeline messages (ci-11b, desktop — `polari-pipeline`):

| type | payload | response payload |
|---|---|---|
| `pipeline.available` | `{}` | `{available, mechanism: "pkexec", verbsProtocol: "polari-pipeline-shell/1", verbs: [ids]}` — `available` is false when no allowlist is installed |
| `pipeline.run` | `{verb, params}` | `{ok, exitCode, output, json?}` — an UNPRIVILEGED verb as the desktop user; `json` appears only when stdout parsed as JSON |
| `pipeline.privileged` | `{verb, params}` | `{ok, exitCode, output}` — a PRIVILEGED verb under `pkexec`, output scrubbed of any secret; never carries `json` |

A page passes a verb **id** and string **parameters** — never an
argv, a flag, a path or a secret. The id must exist in
`/usr/share/polari-pipeline/shell-verbs.json` and carry the matching
`privileged` flag (a privileged verb is refused on `pipeline.run`
and vice versa); every `{param}` is matched against that verb's own
regex; and the resolved `argv[0]` must be one of `pol | apt-get |
usermod | nmcli` — a second allowlist hard-coded in the shell, so a
tampered JSON file cannot introduce a new program. `pkexec` never
sees the real command: it runs the polkit-pinned wrapper
`/usr/lib/polari-pipeline/privileged-run`, which re-validates
everything as root. When a verb declares `stdin: "secret"` the SHELL
opens a native password dialog and writes the value to the child's
stdin — it never enters the page, an argv or a log. Timeouts: 600 s
for `apt-get`, 120 s otherwise. See `pipeline/README.md`.

Events (shell → page): `shell.reachability.changed`, `shell.deeplink`.

## Phase-2 additions (named, not built)

`shell.auth.getToken`, `shell.auth.refresh`, event `shell.auth.updated`
(Strategy B native tokens), `shell.file.download {url, suggestedName}`.
