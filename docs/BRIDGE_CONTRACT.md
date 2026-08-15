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

Events (shell → page): `shell.reachability.changed`, `shell.deeplink`.

## Phase-2 additions (named, not built)

`shell.auth.getToken`, `shell.auth.refresh`, event `shell.auth.updated`
(Strategy B native tokens), `shell.file.download {url, suggestedName}`.
