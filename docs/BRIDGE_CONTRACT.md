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

Events (shell → page): `shell.reachability.changed`, `shell.deeplink`.

## Phase-2 additions (named, not built)

`shell.auth.getToken`, `shell.auth.refresh`, event `shell.auth.updated`
(Strategy B native tokens), `shell.file.download {url, suggestedName}`.
