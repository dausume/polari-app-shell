# PolariShell — iOS/iPadOS (WKWebView)

**This directory cannot be built on the Linux dev box.** It is a
complete, self-contained Swift source tree sharing the repo's
contracts (`config/polari-shell.schema.json`,
`docs/BRIDGE_CONTRACT.md`, the `polari://` deep link, and the
reachability truth table) — deliberately NOT Kotlin Multiplatform
and NOT a Gradle module: one platform did not justify dragging the
whole repo's build complexity up (plan B, phase 4).

## Building (on a Mac)

    brew install xcodegen
    cd ios && xcodegen generate
    open PolariShell.xcodeproj

Then set your signing team and run. iOS 15+.

## What matches the other shells

- Registration document v1 (Codable mirror in `ShellConfig.swift`)
- Add-only registry merge with recorded conflicts
- `polari://register` deep link (short TOFU form + inline payload)
  via `onOpenURL`; the `ca=` fingerprint pin gates the redeem merge
- Probe-first reachability with the same state vocabulary and
  advisory phrasing ("X lives on the '<name>' home network…") —
  keep `ReachabilityTests` mirroring `:core`'s table or the two
  will drift
- Per-instance CA pinning: `URLSession` challenges AND WKWebView
  challenges anchor `SecTrust` to the delivered `caPem` — never a
  global trust change
- Bridge: `window.PolariShell.request()` polyfill over
  `WKScriptMessageHandler` (`PolariShellNative`)

## iOS-specific honesty

- **Config is never baked per-download**: App Store review forbids
  per-user binaries — enrollment is deep-link/QR only (config-free
  install, then `polari://register?...`).
- Auth: the SPA's Keycloak flow runs inside WKWebView; ITP blocks
  the silent-renew iframe's third-party cookies, so expect re-login
  when the access token lapses until Strategy B
  (`ASWebAuthenticationSession` + native refresh, phase 2 of the
  auth plan) lands here.
- Network classification is degraded by design: iOS exposes less
  (`NWPathMonitor` status, no SSID without location entitlements) —
  wrong-network vs instance-down phrasing leans on the declared
  CIDRs against interface addresses, same as everywhere else.
