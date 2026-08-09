
## 32. Unified isle store + agent-bootstrapping store shell (2026-08-08)

Dustin's clarifications, BUILT:
- **The isle app store is the GENUINE store**; polari's app store is
  PROJECTED in when a polari instance is available (polari-app
  catalog entries). One app, TABS — `/isle` (IsleHubComponent):
  **App store** + **Topology** tabs. Browser-VERIFIED both tabs
  render.
- **Install polari apps + modules through the store, feeling
  native**: catalog polari-app install = ONE `isle shell launcher
  --name <a> --title <T> --url <u> --install` → builds+installs a
  launcher .deb sharing the one polari-shell-core runtime (native
  feel). polari-module = `isle module install <m>` (topology-assign
  path now; module .deb when the repo lands). New verbs wired:
  `isle shell`, `isle module`, `isle agent ensure`.
- **The store shell ALWAYS installs an agent** (the emphasized
  rule): `build-store-deb.sh` → isle-app-store .deb whose POSTINST
  runs `isle trust install` + `isle agent ensure` — installing the
  store makes the device a full isle member that can HOST, not just
  reach. Depends: polari-shell-core, policykit-1; opens `/isle`.
  Staged on all 3 devices.

So: one native store window (catalog + topology tabs, password-
prompted install), installing it bootstraps mesh membership + an
agent, and polari apps/modules install through it as native apps.
Per device: `sudo apt install ~/polari-shells/isle-app-store_*.deb`
(pulls the shared core, ensures the agent) → "Isle App Store" in
the menu.

⚠ honest limits: `isle agent ensure` brings up an agent where the
isle CLI + agent files exist (isle-core: proven "already running").
On a truly fresh device the agent files must be present first
(isle install) — the postinst says so. Module .deb repo = mac-8
(topology-assign works now). GUI click-through per device = Dustin.
